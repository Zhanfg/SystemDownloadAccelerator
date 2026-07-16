use anyhow::Result;
use serde::Serialize;

#[derive(Debug, Serialize)]
struct CliStatus<'a> {
    command: &'a str,
    protocol_version: u32,
    daemon_socket: &'a str,
    implementation: &'a str,
}

fn main() -> Result<()> {
    let command = std::env::args().nth(1).unwrap_or_else(|| "status".into());
    let status = CliStatus {
        command: &command,
        protocol_version: sda_protocol::PROTOCOL_VERSION,
        daemon_socket: "/data/adb/sda/run/control.sock",
        implementation: "rust-rewrite-skeleton",
    };
    println!("{}", serde_json::to_string_pretty(&status)?);
    Ok(())
}
