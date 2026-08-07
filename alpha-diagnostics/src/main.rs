use std::collections::BTreeSet;
use std::env;
use std::ffi::OsStr;
use std::fs;
use std::os::fd::RawFd;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

const PACKAGE_NAME: &str = "io.github.zhanfg.sda";
const DOWNLOAD_PROCESS: &str = "com.android.providers.downloads";
const SYSTEM_UI_PROCESS: &str = "com.android.systemui";
const MODULES_DIR: &str = "/data/adb/modules";
const MAX_MAP_EVIDENCE: usize = 40;

#[derive(Debug, Clone)]
struct ProcessInfo {
    pid: u32,
    ppid: u32,
    start_time_ticks: u64,
    command: String,
    comm: String,
    executable: String,
    uid: Option<u32>,
    gid: Option<u32>,
    selinux: String,
    pidfd_alive: Option<bool>,
}

impl ProcessInfo {
    fn label(&self) -> &str {
        if !self.command.is_empty() {
            self.command.split_whitespace().next().unwrap_or(&self.comm)
        } else {
            &self.comm
        }
    }
}

#[derive(Debug, Clone)]
struct ProviderReport {
    root_manager: String,
    candidates: Vec<String>,
    conflict: bool,
    evidence: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Verdict {
    Pass,
    Partial,
    Waiting,
    Conflict,
}

impl Verdict {
    fn as_str(self) -> &'static str {
        match self {
            Self::Pass => "PASS",
            Self::Partial => "PARTIAL",
            Self::Waiting => "WAITING",
            Self::Conflict => "CONFLICT",
        }
    }
}

fn main() {
    let module_dir = parse_module_dir();
    let now_ms = now_ms();
    let processes = scan_processes();
    let provider = detect_provider();
    let zygotes = processes
        .iter()
        .filter(|process| is_zygote(process.label()))
        .cloned()
        .collect::<Vec<_>>();
    let usaps = processes
        .iter()
        .filter(|process| is_usap(process.label()))
        .cloned()
        .collect::<Vec<_>>();
    let download = processes
        .iter()
        .find(|process| process_matches(process, DOWNLOAD_PROCESS))
        .cloned();
    let system_ui = processes
        .iter()
        .find(|process| process_matches(process, SYSTEM_UI_PROCESS))
        .cloned();

    let mapping_evidence = download
        .as_ref()
        .map(|process| collect_mapping_evidence(process.pid, &module_dir))
        .unwrap_or_default();
    let alpha_mapped = mapping_evidence.iter().any(|line| {
        let normalized = line.to_ascii_lowercase();
        normalized.contains(PACKAGE_NAME)
            || normalized.contains("systemdownloadaccelerator")
            || normalized.contains("system_download_accelerator")
            || normalized.contains("sda-alpha")
    });

    let verdict = if provider.conflict {
        Verdict::Conflict
    } else if download.is_none() {
        Verdict::Waiting
    } else if alpha_mapped {
        Verdict::Pass
    } else {
        Verdict::Partial
    };

    println!("SystemDownloadAccelerator Alpha one-shot diagnostics");
    println!("timestamp_ms={now_ms}");
    println!("verdict={}", verdict.as_str());
    println!("read_only=true");
    println!();

    print_module_section(&module_dir);
    print_provider_section(&provider);
    print_process_group("zygote", &zygotes);
    print_process_group("usap", &usaps);
    print_named_process("download_provider", download.as_ref());
    print_named_process("system_ui", system_ui.as_ref());

    println!("[download_provider_mapping]");
    println!("alpha_mapping_detected={alpha_mapped}");
    println!("evidence_count={}", mapping_evidence.len());
    for line in &mapping_evidence {
        println!("map={line}");
    }
    println!();

    println!("[summary_json]");
    println!(
        "{{\"verdict\":\"{}\",\"read_only\":true,\"root_manager\":\"{}\",\"provider_conflict\":{},\"provider_candidates\":{},\"zygote_count\":{},\"usap_count\":{},\"download_provider_running\":{},\"download_provider_pid\":{},\"system_ui_pid\":{},\"alpha_mapping_detected\":{},\"module_dir\":\"{}\"}}",
        verdict.as_str(),
        json_escape(&provider.root_manager),
        provider.conflict,
        json_array(&provider.candidates),
        zygotes.len(),
        usaps.len(),
        download.is_some(),
        download.as_ref().map_or(0, |process| process.pid),
        system_ui.as_ref().map_or(0, |process| process.pid),
        alpha_mapped,
        json_escape(&module_dir.to_string_lossy()),
    );
}

fn parse_module_dir() -> PathBuf {
    let mut arguments = env::args_os().skip(1);
    while let Some(argument) = arguments.next() {
        if argument == OsStr::new("--module") {
            if let Some(value) = arguments.next() {
                return PathBuf::from(value);
            }
        }
    }
    env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("/data/adb/modules/system_download_accelerator"))
}

fn print_module_section(module_dir: &Path) {
    println!("[module]");
    println!("path={}", module_dir.display());
    println!("exists={}", module_dir.is_dir());
    println!("disabled={}", module_dir.join("disable").exists());
    println!("pending_remove={}", module_dir.join("remove").exists());
    print_file_state("module_prop", &module_dir.join("module.prop"));
    print_file_state("action_script", &module_dir.join("action.sh"));
    print_file_state("detector", &module_dir.join("bin/sda-alpha-detect"));
    print_file_state("embedded_apk", &module_dir.join("apk/SystemDownloadAccelerator.apk"));
    if let Ok(properties) = fs::read_to_string(module_dir.join("module.prop")) {
        for line in properties.lines().filter(|line| {
            line.starts_with("id=")
                || line.starts_with("name=")
                || line.starts_with("version=")
                || line.starts_with("versionCode=")
        }) {
            println!("property={line}");
        }
    }
    println!();
}

fn print_file_state(name: &str, path: &Path) {
    let size = fs::metadata(path).map(|metadata| metadata.len()).unwrap_or(0);
    println!("{name}_exists={}", path.is_file());
    println!("{name}_size={size}");
}

fn print_provider_section(report: &ProviderReport) {
    println!("[root_and_provider]");
    println!("root_manager={}", report.root_manager);
    println!("provider_conflict={}", report.conflict);
    println!("provider_candidates={}", report.candidates.join(","));
    for evidence in &report.evidence {
        println!("provider_evidence={evidence}");
    }
    println!();
}

fn print_process_group(name: &str, processes: &[ProcessInfo]) {
    println!("[{name}]");
    println!("count={}", processes.len());
    for process in processes {
        println!("process={}", process_line(process));
    }
    println!();
}

fn print_named_process(name: &str, process: Option<&ProcessInfo>) {
    println!("[{name}]");
    match process {
        Some(process) => {
            println!("running=true");
            println!("pid={}", process.pid);
            println!("ppid={}", process.ppid);
            println!("start_time_ticks={}", process.start_time_ticks);
            println!("uid={}", option_number(process.uid));
            println!("gid={}", option_number(process.gid));
            println!("command={}", sanitize_line(&process.command));
            println!("comm={}", sanitize_line(&process.comm));
            println!("executable={}", sanitize_line(&process.executable));
            println!("selinux={}", sanitize_line(&process.selinux));
            println!("pidfd_alive={}", option_bool(process.pidfd_alive));
        }
        None => println!("running=false"),
    }
    println!();
}

fn process_line(process: &ProcessInfo) -> String {
    format!(
        "pid={} ppid={} start={} uid={} gid={} command={} comm={} exe={} selinux={} pidfd_alive={}",
        process.pid,
        process.ppid,
        process.start_time_ticks,
        option_number(process.uid),
        option_number(process.gid),
        sanitize_line(&process.command),
        sanitize_line(&process.comm),
        sanitize_line(&process.executable),
        sanitize_line(&process.selinux),
        option_bool(process.pidfd_alive),
    )
}

fn option_number(value: Option<u32>) -> String {
    value.map_or_else(|| "unknown".to_owned(), |number| number.to_string())
}

fn option_bool(value: Option<bool>) -> &'static str {
    match value {
        Some(true) => "true",
        Some(false) => "false",
        None => "unsupported",
    }
}

fn scan_processes() -> Vec<ProcessInfo> {
    let Ok(entries) = fs::read_dir("/proc") else {
        return Vec::new();
    };
    let mut processes = entries
        .flatten()
        .filter_map(|entry| {
            let pid = entry.file_name().to_str()?.parse::<u32>().ok()?;
            read_process(pid)
        })
        .collect::<Vec<_>>();
    processes.sort_by_key(|process| process.pid);
    processes
}

fn read_process(pid: u32) -> Option<ProcessInfo> {
    let root = PathBuf::from(format!("/proc/{pid}"));
    let stat = fs::read_to_string(root.join("stat")).ok()?;
    let (ppid, start_time_ticks) = parse_stat(&stat)?;
    let command = fs::read(root.join("cmdline"))
        .map(|bytes| String::from_utf8_lossy(&bytes).replace('\0', " ").trim().to_owned())
        .unwrap_or_default();
    let comm = fs::read_to_string(root.join("comm"))
        .map(|value| value.trim().to_owned())
        .unwrap_or_default();
    let executable = fs::read_link(root.join("exe"))
        .map(|path| path.to_string_lossy().into_owned())
        .unwrap_or_default();
    let status = fs::read_to_string(root.join("status")).unwrap_or_default();
    let uid = status_number(&status, "Uid:");
    let gid = status_number(&status, "Gid:");
    let selinux = fs::read_to_string(root.join("attr/current"))
        .map(|value| value.trim().to_owned())
        .unwrap_or_default();

    Some(ProcessInfo {
        pid,
        ppid,
        start_time_ticks,
        command,
        comm,
        executable,
        uid,
        gid,
        selinux,
        pidfd_alive: pidfd_alive(pid),
    })
}

fn parse_stat(stat: &str) -> Option<(u32, u64)> {
    let close = stat.rfind(')')?;
    let fields = stat.get(close + 1..)?.split_whitespace().collect::<Vec<_>>();
    let ppid = fields.get(1)?.parse::<u32>().ok()?;
    let start_time = fields.get(19)?.parse::<u64>().ok()?;
    Some((ppid, start_time))
}

fn status_number(status: &str, key: &str) -> Option<u32> {
    status
        .lines()
        .find(|line| line.starts_with(key))
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|value| value.parse::<u32>().ok())
}

fn pidfd_alive(pid: u32) -> Option<bool> {
    // SAFETY: pidfd_open takes an integer PID and flags=0; no caller memory is exposed.
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid as libc::pid_t, 0) } as RawFd;
    if fd >= 0 {
        // SAFETY: fd was returned by pidfd_open and is owned by this function.
        unsafe { libc::close(fd) };
        return Some(true);
    }
    let error = std::io::Error::last_os_error().raw_os_error();
    match error {
        Some(libc::ENOSYS | libc::EINVAL) => None,
        Some(libc::ESRCH) => Some(false),
        _ => None,
    }
}

fn process_matches(process: &ProcessInfo, expected: &str) -> bool {
    process.command.split_whitespace().next() == Some(expected)
        || process.command == expected
        || process.comm == expected
}

fn is_zygote(label: &str) -> bool {
    matches!(label, "zygote" | "zygote32" | "zygote64")
}

fn is_usap(label: &str) -> bool {
    label.contains("usap32") || label.contains("usap64") || label.contains("usap_pool")
}

fn collect_mapping_evidence(pid: u32, module_dir: &Path) -> Vec<String> {
    let mut evidence = BTreeSet::new();
    let module_text = module_dir.to_string_lossy().to_ascii_lowercase();
    if let Ok(maps) = fs::read_to_string(format!("/proc/{pid}/maps")) {
        for line in maps.lines() {
            let normalized = line.to_ascii_lowercase();
            if normalized.contains(&module_text)
                || normalized.contains(PACKAGE_NAME)
                || normalized.contains("systemdownloadaccelerator")
                || normalized.contains("system_download_accelerator")
                || normalized.contains("sda-alpha")
            {
                evidence.insert(sanitize_line(line));
                if evidence.len() >= MAX_MAP_EVIDENCE {
                    break;
                }
            }
        }
    }

    if evidence.len() < MAX_MAP_EVIDENCE {
        if let Ok(entries) = fs::read_dir(format!("/proc/{pid}/fd")) {
            for entry in entries.flatten() {
                let Ok(target) = fs::read_link(entry.path()) else {
                    continue;
                };
                let text = target.to_string_lossy();
                let normalized = text.to_ascii_lowercase();
                if normalized.contains(&module_text)
                    || normalized.contains(PACKAGE_NAME)
                    || normalized.contains("systemdownloadaccelerator")
                    || normalized.contains("system_download_accelerator")
                    || normalized.contains("sda-alpha")
                {
                    evidence.insert(format!("fd:{} -> {}", entry.file_name().to_string_lossy(), text));
                    if evidence.len() >= MAX_MAP_EVIDENCE {
                        break;
                    }
                }
            }
        }
    }
    evidence.into_iter().collect()
}

fn detect_provider() -> ProviderReport {
    let root_manager = detect_root_manager();
    let mut candidates = BTreeSet::new();
    let mut evidence = Vec::new();

    if let Ok(entries) = fs::read_dir(MODULES_DIR) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_dir() || path.join("disable").exists() || path.join("remove").exists() {
                continue;
            }
            let identity = module_identity(&path).to_ascii_lowercase();
            let path_text = path.to_string_lossy().to_ascii_lowercase();
            let candidate = if identity.contains("rezygisk") || path_text.contains("rezygisk") {
                Some("rezygisk")
            } else if identity.contains("zygisk next")
                || identity.contains("zygisknext")
                || path_text.contains("zygisk_next")
                || path_text.contains("zygisknext")
            {
                Some("zygisk-next")
            } else if identity.contains("zygisksu") || path_text.contains("zygisksu") {
                Some("zygisksu")
            } else {
                None
            };
            if let Some(candidate) = candidate {
                candidates.insert(candidate.to_owned());
                evidence.push(format!("active_module:{}", path.display()));
            }
        }
    }

    for process in scan_processes() {
        let command = process.command.to_ascii_lowercase();
        if command.contains("rezygiskd") {
            candidates.insert("rezygisk".to_owned());
            evidence.push(format!("process:{}:{}", process.pid, sanitize_line(&process.command)));
        } else if command.contains("zygisknext") || command.contains("zygisk_next") {
            candidates.insert("zygisk-next".to_owned());
            evidence.push(format!("process:{}:{}", process.pid, sanitize_line(&process.command)));
        }
    }

    if candidates.is_empty() && root_manager == "magisk" && magisk_builtin_zygisk_enabled() {
        candidates.insert("magisk-builtin".to_owned());
        evidence.push("magisk_sqlite:zygisk=1".to_owned());
    }

    let candidates = candidates.into_iter().collect::<Vec<_>>();
    ProviderReport {
        root_manager,
        conflict: candidates.len() > 1,
        candidates,
        evidence,
    }
}

fn detect_root_manager() -> String {
    if Path::new("/data/adb/ksu").exists() || Path::new("/data/adb/ksud").exists() {
        return "kernelsu".to_owned();
    }
    if Path::new("/data/adb/ap").exists() || Path::new("/data/adb/apatch").exists() {
        return "apatch".to_owned();
    }
    if Path::new("/data/adb/magisk").exists()
        || Path::new("/data/adb/magisk.db").exists()
        || Path::new("/sbin/.magisk").exists()
    {
        return "magisk".to_owned();
    }
    "unknown".to_owned()
}

fn magisk_builtin_zygisk_enabled() -> bool {
    let commands = ["magisk", "/data/adb/magisk/magisk"];
    commands.iter().any(|command| {
        Command::new(command)
            .args(["--sqlite", "SELECT value FROM settings WHERE key='zygisk';"])
            .output()
            .ok()
            .filter(|output| output.status.success())
            .map(|output| String::from_utf8_lossy(&output.stdout).contains('1'))
            .unwrap_or(false)
    })
}

fn module_identity(module: &Path) -> String {
    let mut identity = module
        .file_name()
        .and_then(OsStr::to_str)
        .unwrap_or_default()
        .to_owned();
    if let Ok(properties) = fs::read_to_string(module.join("module.prop")) {
        for line in properties.lines().filter(|line| {
            line.starts_with("id=") || line.starts_with("name=") || line.starts_with("description=")
        }) {
            identity.push(' ');
            identity.push_str(line);
        }
    }
    identity
}

fn sanitize_line(value: &str) -> String {
    value.replace(['\n', '\r', '\t'], " ").trim().to_owned()
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            control if control.is_control() => {
                use std::fmt::Write as _;
                let _ = write!(escaped, "\\u{:04x}", control as u32);
            }
            other => escaped.push(other),
        }
    }
    escaped
}

fn json_array(values: &[String]) -> String {
    format!(
        "[{}]",
        values
            .iter()
            .map(|value| format!("\"{}\"", json_escape(value)))
            .collect::<Vec<_>>()
            .join(",")
    )
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_proc_stat_after_parenthesized_name() {
        let stat = "123 (name with space) S 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 98765 0";
        assert_eq!(parse_stat(stat), Some((1, 98765)));
    }

    #[test]
    fn json_escaping_is_stable() {
        assert_eq!(json_escape("a\"b\\c\n"), "a\\\"b\\\\c\\n");
    }

    #[test]
    fn verdict_strings_are_stable() {
        assert_eq!(Verdict::Pass.as_str(), "PASS");
        assert_eq!(Verdict::Partial.as_str(), "PARTIAL");
    }
}
