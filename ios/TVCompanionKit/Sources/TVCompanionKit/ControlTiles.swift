import Foundation

/// What one Controls tile shows. Only states the TV reported; unknown values disable the tile alone.
public struct ControlTile: Equatable {
    public let isOn: Bool
    public let enabled: Bool
    public let detail: String
    public let inProgress: Bool
    public init(isOn: Bool, enabled: Bool, detail: String, inProgress: Bool = false) {
        self.isOn = isOn; self.enabled = enabled; self.detail = detail; self.inProgress = inProgress
    }
    static let unknown = ControlTile(isOn: false, enabled: false, detail: "Unknown")
}

public extension ControlStatus {
    var lightTile: ControlTile {
        switch light.state {
        case "on": return ControlTile(isOn: true, enabled: true, detail: light.preset.map { "On · \($0.capitalized)" } ?? "On")
        case "off": return ControlTile(isOn: false, enabled: true, detail: "Off")
        case "busy": return ControlTile(isOn: false, enabled: false, detail: "Busy", inProgress: true)
        case "unavailable": return ControlTile(isOn: false, enabled: false, detail: "Not responding")
        case "not_set_up": return ControlTile(isOn: false, enabled: false, detail: "Not set up on the TV")
        default: return .unknown
        }
    }
    var ambilightTile: ControlTile {
        switch ambilight.state {
        case "running": return ControlTile(isOn: true, enabled: true, detail: "Running")
        case "starting": return ControlTile(isOn: true, enabled: true, detail: "Starting…", inProgress: true)
        case "needs_approval": return ControlTile(isOn: true, enabled: true, detail: "Approve on the TV", inProgress: true)
        case "stopping": return ControlTile(isOn: false, enabled: false, detail: "Stopping…", inProgress: true)
        case "off": return ControlTile(isOn: false, enabled: true, detail: "Off")
        case "error": return ControlTile(isOn: false, enabled: true, detail: "Stopped with an error")
        case "not_set_up": return ControlTile(isOn: false, enabled: false, detail: "Set up on the TV first")
        default: return .unknown
        }
    }
    var ambientTile: ControlTile {
        let look = (ambient.look ?? ambient.looks.first).map(ControlNames.look)
        switch ambient.state {
        case "showing": return ControlTile(isOn: true, enabled: true, detail: look.map { "Showing · \($0)" } ?? "Showing")
        case "available": return ControlTile(isOn: false, enabled: look != nil, detail: look.map { "Off · \($0)" } ?? "Off")
        case "unavailable": return ControlTile(isOn: false, enabled: false, detail: "Play music over AirPlay")
        default: return .unknown
        }
    }
    var notificationsTile: ControlTile {
        switch notifications.state {
        case "listening": return ControlTile(isOn: true, enabled: true, detail: "Listening")
        case "starting": return ControlTile(isOn: true, enabled: true, detail: "Starting…", inProgress: true)
        case "paused": return ControlTile(isOn: false, enabled: true, detail: "Paused")
        case "failed": return ControlTile(isOn: false, enabled: true, detail: "Failed on the TV")
        default: return .unknown
        }
    }
    /// On means "available in background"; the detail says what the receiver is doing.
    var airplayTile: ControlTile {
        let on = airplay.available
        switch airplay.state {
        case "playing": return ControlTile(isOn: on, enabled: true, detail: "Playing now")
        case "ready": return ControlTile(isOn: on, enabled: true, detail: "Ready")
        case "starting": return ControlTile(isOn: on, enabled: true, detail: "Starting…", inProgress: true)
        case "stopped": return ControlTile(isOn: on, enabled: true, detail: "Off")
        case "failed": return ControlTile(isOn: on, enabled: true, detail: "Failed on the TV")
        default: return .unknown
        }
    }
}

/// Readable names for wire IDs such as `chromatic_flow`.
public enum ControlNames {
    public static func look(_ id: String) -> String { id.replacingOccurrences(of: "_", with: " ").capitalized }
    /// One-line explanations matching the TV's Ambilight screen; unknown names get none.
    public static func ambilightDetail(_ id: String) -> String? {
        ["cinema": "Slow, calm changes for films", "responsive": "Quick changes for games and sport",
         "soft": "Gentle glow in the background", "normal": "Lights match the picture",
         "dramatic": "Dark scenes glow less, bright ones stay bright"][id]
    }
}
