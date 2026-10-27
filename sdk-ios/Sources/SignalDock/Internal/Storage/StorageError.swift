/// A storage failure. It carries codes only: never SQL text, values, paths, or system messages (ADR-0017).
///
/// Every storage call throws this type and no other, so the layer above maps one type to
/// `InitError.StorageFailure`, `TrackResult.storageError`, and `storageAvailable = false`.
internal struct StorageError: Error, Equatable, Sendable {
  internal enum Kind: String, Sendable {
    /// SQLite returned an error. `code` is the extended result code.
    case sqlite
    /// A file operation failed. `code` is `errno`.
    case file
    /// The database has a schema version that this SDK does not know (a downgrade), or a migration is missing.
    case schema
    /// The store was closed.
    case closed
  }

  internal let kind: Kind
  internal let code: Int32

  internal init(_ kind: Kind, _ code: Int32 = 0) {
    self.kind = kind
    self.code = code
  }
}
