/// Runs `command` on `target`, the player or its engine. Without one, as before setup, the command
/// is dropped and `warn` gets a line naming the call (mirrors Android `commandPlayer`).
func runPlayerCommand<Target>(
  _ name: String,
  on target: Target?,
  warn: (String) -> Void,
  _ command: (Target) throws -> Void,
) rethrows {
  guard let target else {
    warn("\(name) before the player is set up: ignored")
    return
  }
  try command(target)
}
