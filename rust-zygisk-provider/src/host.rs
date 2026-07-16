use std::fs;
use std::path::{Path, PathBuf};

const MODULES_DIR: &str = "/data/adb/modules";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostReport {
    pub root_manager: String,
    pub provider: String,
    pub confidence: u8,
    pub conflict: bool,
    pub evidence: Vec<String>,
}

impl HostReport {
    pub fn detect() -> Self {
        let root_manager = detect_root_manager();
        let mut candidates = Vec::new();
        let mut evidence = Vec::new();

        for module in active_modules() {
            let identity = module_identity(&module);
            let normalized = identity.to_ascii_lowercase();
            let path_text = module.to_string_lossy().to_ascii_lowercase();

            if normalized.contains("rezygisk") || path_text.contains("rezygisk") {
                push_candidate(&mut candidates, "rezygisk");
                evidence.push(format!("active-module:{}", module.display()));
            } else if normalized.contains("zygisk next")
                || normalized.contains("zygisknext")
                || path_text.contains("zygisk_next")
                || path_text.contains("zygisknext")
            {
                push_candidate(&mut candidates, "zygisk-next");
                evidence.push(format!("active-module:{}", module.display()));
            } else if normalized.contains("zygisksu") || path_text.contains("zygisksu") {
                push_candidate(&mut candidates, "zygisksu");
                evidence.push(format!("active-module:{}", module.display()));
            }
        }

        for (pid, command) in process_commands() {
            let normalized = command.to_ascii_lowercase();
            if normalized.contains("rezygiskd") {
                push_candidate(&mut candidates, "rezygisk");
                evidence.push(format!("process:{pid}:{command}"));
            } else if normalized.contains("zygiskd") {
                evidence.push(format!("process:{pid}:{command}"));
            }
        }

        let conflict = candidates.len() > 1;
        let (provider, confidence) = if conflict {
            (format!("conflict:{}", candidates.join(",")), 100)
        } else if let Some(provider) = candidates.first() {
            (provider.clone(), 95)
        } else if root_manager == "magisk" && magisk_zygisk_evidence(&mut evidence) {
            ("magisk-builtin".to_owned(), 70)
        } else {
            ("unknown".to_owned(), 0)
        };

        Self {
            root_manager,
            provider,
            confidence,
            conflict,
            evidence,
        }
    }

    pub fn to_json(&self) -> String {
        let evidence = self
            .evidence
            .iter()
            .map(|item| format!("\"{}\"", json_escape(item)))
            .collect::<Vec<_>>()
            .join(",");
        format!(
            "{{\"root_manager\":\"{}\",\"provider\":\"{}\",\"confidence\":{},\"conflict\":{},\"evidence\":[{}]}}",
            json_escape(&self.root_manager),
            json_escape(&self.provider),
            self.confidence,
            self.conflict,
            evidence
        )
    }
}

fn detect_root_manager() -> String {
    if Path::new("/data/adb/magisk").exists()
        || Path::new("/data/adb/magisk.db").exists()
        || Path::new("/sbin/.magisk").exists()
    {
        return "magisk".to_owned();
    }
    if Path::new("/data/adb/ksu").exists() || Path::new("/data/adb/ksud").exists() {
        return "kernelsu".to_owned();
    }
    if Path::new("/data/adb/ap").exists() || Path::new("/data/adb/apatch").exists() {
        return "apatch".to_owned();
    }
    "unknown".to_owned()
}

fn active_modules() -> Vec<PathBuf> {
    let mut modules = Vec::new();
    let Ok(entries) = fs::read_dir(MODULES_DIR) else {
        return modules;
    };

    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() || path.join("disable").exists() || path.join("remove").exists() {
            continue;
        }
        if path.join("module.prop").is_file() {
            modules.push(path);
        }
    }
    modules
}

fn module_identity(module: &Path) -> String {
    let mut identity = module
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default()
        .to_owned();
    if let Ok(properties) = fs::read_to_string(module.join("module.prop")) {
        for line in properties.lines() {
            if line.starts_with("id=") || line.starts_with("name=") || line.starts_with("description=") {
                identity.push(' ');
                identity.push_str(line);
            }
        }
    }
    identity
}

fn process_commands() -> Vec<(u32, String)> {
    let mut commands = Vec::new();
    let Ok(entries) = fs::read_dir("/proc") else {
        return commands;
    };

    for entry in entries.flatten() {
        let Some(pid_text) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(pid) = pid_text.parse::<u32>() else {
            continue;
        };
        let Ok(bytes) = fs::read(entry.path().join("cmdline")) else {
            continue;
        };
        let command = String::from_utf8_lossy(&bytes)
            .replace('\0', " ")
            .trim()
            .to_owned();
        if !command.is_empty() {
            commands.push((pid, command));
        }
    }
    commands
}

fn magisk_zygisk_evidence(evidence: &mut Vec<String>) -> bool {
    let mut found = false;
    for (_, command) in process_commands() {
        if command == "zygote" || command == "zygote64" || command.ends_with("/zygote64") {
            found = true;
            evidence.push(format!("zygote-process:{command}"));
        }
    }
    if Path::new("/data/adb/magisk.db").exists() {
        evidence.push("magisk-database-present".to_owned());
    }
    found
}

fn push_candidate(candidates: &mut Vec<String>, candidate: &str) {
    if !candidates.iter().any(|item| item == candidate) {
        candidates.push(candidate.to_owned());
    }
}

pub fn json_escape(value: &str) -> String {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn report_json_has_stable_shape() {
        let report = HostReport {
            root_manager: "magisk".to_owned(),
            provider: "rezygisk".to_owned(),
            confidence: 95,
            conflict: false,
            evidence: vec!["active-module:/data/adb/modules/rezygisk".to_owned()],
        };
        let json = report.to_json();
        assert!(json.contains("\"provider\":\"rezygisk\""));
        assert!(json.contains("\"confidence\":95"));
    }

    #[test]
    fn escaping_is_valid_for_control_characters() {
        assert_eq!(json_escape("a\"b\\c\n"), "a\\\"b\\\\c\\n");
    }
}
