use anyhow::Result;
use serde::Serialize;

#[derive(Debug, Serialize)]
struct StartupStatus<'a> {
    daemon: &'a str,
    protocol_version: u32,
    control_socket: &'a str,
    plugin_socket: &'a str,
    safe_mode: bool,
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "sdad=info".into()),
        )
        .init();

    let status = StartupStatus {
        daemon: "sdad",
        protocol_version: sda_protocol::PROTOCOL_VERSION,
        control_socket: "/data/adb/sda/run/control.sock",
        plugin_socket: "/data/adb/sda/run/plugin.sock",
        safe_mode: false,
    };

    tracing::info!(status = %serde_json::to_string(&status)?, "daemon skeleton initialized");
    Ok(())
}
