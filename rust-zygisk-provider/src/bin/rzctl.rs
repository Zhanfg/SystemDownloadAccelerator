use std::os::unix::net::UnixStream;
use std::time::Duration;

use rz_runtime::protocol::{Message, MessageKind};

const SOCKET_PATH: &str = "/data/adb/rzruntime/run/control.sock";

fn main() {
    if let Err(error) = run() {
        eprintln!("rzctl: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let command = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "status".to_owned());

    match command.as_str() {
        "status" | "ping" => query_status(),
        "help" | "--help" | "-h" => {
            println!("Usage: rzctl [status|ping]");
            Ok(())
        }
        other => Err(format!("unknown command: {other}")),
    }
}

fn query_status() -> Result<(), String> {
    let mut stream = UnixStream::connect(SOCKET_PATH).map_err(|error| {
        format!("cannot connect to runtime daemon at {SOCKET_PATH}: {error}")
    })?;
    stream
        .set_read_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;
    stream
        .set_write_timeout(Some(Duration::from_secs(2)))
        .map_err(|error| error.to_string())?;

    Message::new(MessageKind::QueryStatus, std::process::id(), 0, "")
        .write_to(&mut stream)
        .map_err(|error| error.to_string())?;
    let response = Message::read_from(&mut stream).map_err(|error| error.to_string())?;

    match response.kind {
        MessageKind::Status => {
            println!("{}", response.body);
            Ok(())
        }
        MessageKind::Error => Err(response.body),
        other => Err(format!("unexpected response: {other:?}")),
    }
}
