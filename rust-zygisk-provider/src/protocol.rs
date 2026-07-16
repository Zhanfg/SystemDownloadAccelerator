use std::io::{self, Read, Write};

pub const PROTOCOL_VERSION: u16 = 1;
const MAGIC: [u8; 4] = *b"RZG1";
const HEADER_LEN: usize = 20;
const MAX_BODY_LEN: usize = 64 * 1024;

#[repr(u16)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageKind {
    RegisterProcess = 1,
    Ack = 2,
    QueryStatus = 3,
    Status = 4,
    Error = 255,
}

impl TryFrom<u16> for MessageKind {
    type Error = ProtocolError;

    fn try_from(value: u16) -> Result<Self, ProtocolError> {
        match value {
            1 => Ok(Self::RegisterProcess),
            2 => Ok(Self::Ack),
            3 => Ok(Self::QueryStatus),
            4 => Ok(Self::Status),
            255 => Ok(Self::Error),
            other => Err(ProtocolError::UnknownKind(other)),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Message {
    pub kind: MessageKind,
    pub pid: u32,
    pub uid: u32,
    pub body: String,
}

impl Message {
    pub fn new(kind: MessageKind, pid: u32, uid: u32, body: impl Into<String>) -> Self {
        Self {
            kind,
            pid,
            uid,
            body: body.into(),
        }
    }

    pub fn write_to(&self, writer: &mut impl Write) -> Result<(), ProtocolError> {
        let body = self.body.as_bytes();
        if body.len() > MAX_BODY_LEN {
            return Err(ProtocolError::BodyTooLarge(body.len()));
        }

        let mut header = [0_u8; HEADER_LEN];
        header[0..4].copy_from_slice(&MAGIC);
        header[4..6].copy_from_slice(&PROTOCOL_VERSION.to_le_bytes());
        header[6..8].copy_from_slice(&(self.kind as u16).to_le_bytes());
        header[8..12].copy_from_slice(&self.pid.to_le_bytes());
        header[12..16].copy_from_slice(&self.uid.to_le_bytes());
        header[16..20].copy_from_slice(&(body.len() as u32).to_le_bytes());

        writer.write_all(&header)?;
        writer.write_all(body)?;
        writer.flush()?;
        Ok(())
    }

    pub fn read_from(reader: &mut impl Read) -> Result<Self, ProtocolError> {
        let mut header = [0_u8; HEADER_LEN];
        reader.read_exact(&mut header)?;

        if header[0..4] != MAGIC {
            return Err(ProtocolError::BadMagic);
        }

        let version = u16::from_le_bytes([header[4], header[5]]);
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }

        let kind = MessageKind::try_from(u16::from_le_bytes([header[6], header[7]]))?;
        let pid = u32::from_le_bytes(header[8..12].try_into().expect("fixed slice"));
        let uid = u32::from_le_bytes(header[12..16].try_into().expect("fixed slice"));
        let body_len =
            u32::from_le_bytes(header[16..20].try_into().expect("fixed slice")) as usize;

        if body_len > MAX_BODY_LEN {
            return Err(ProtocolError::BodyTooLarge(body_len));
        }

        let mut body = vec![0_u8; body_len];
        reader.read_exact(&mut body)?;
        let body = String::from_utf8(body).map_err(ProtocolError::InvalidUtf8)?;

        Ok(Self {
            kind,
            pid,
            uid,
            body,
        })
    }
}

#[derive(Debug)]
pub enum ProtocolError {
    Io(io::Error),
    BadMagic,
    UnsupportedVersion(u16),
    UnknownKind(u16),
    BodyTooLarge(usize),
    InvalidUtf8(std::string::FromUtf8Error),
}

impl std::fmt::Display for ProtocolError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(error) => write!(formatter, "I/O error: {error}"),
            Self::BadMagic => formatter.write_str("invalid protocol magic"),
            Self::UnsupportedVersion(version) => {
                write!(formatter, "unsupported protocol version {version}")
            }
            Self::UnknownKind(kind) => write!(formatter, "unknown message kind {kind}"),
            Self::BodyTooLarge(size) => write!(formatter, "message body too large: {size} bytes"),
            Self::InvalidUtf8(error) => write!(formatter, "invalid UTF-8 body: {error}"),
        }
    }
}

impl std::error::Error for ProtocolError {}

impl From<io::Error> for ProtocolError {
    fn from(value: io::Error) -> Self {
        Self::Io(value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_round_trip() {
        let message = Message::new(
            MessageKind::RegisterProcess,
            1234,
            10068,
            "com.android.providers.downloads",
        );
        let mut encoded = Vec::new();
        message.write_to(&mut encoded).unwrap();
        let decoded = Message::read_from(&mut encoded.as_slice()).unwrap();
        assert_eq!(decoded, message);
    }

    #[test]
    fn rejects_wrong_magic() {
        let mut bytes = vec![0_u8; HEADER_LEN];
        bytes[0..4].copy_from_slice(b"NOPE");
        let error = Message::read_from(&mut bytes.as_slice()).unwrap_err();
        assert!(matches!(error, ProtocolError::BadMagic));
    }
}
