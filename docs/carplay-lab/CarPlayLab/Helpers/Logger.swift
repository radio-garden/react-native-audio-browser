/*
See the LICENSE.txt file for this sample’s licensing information.

Abstract:
`Logger` handles logging of events during a CarPlay session.
*/

import Foundation

struct Event: Hashable {
    let date: Date!
    let text: String!
}

/// `Logger` describes an object that can receive events from elsewhere in the app and persist them to memory, disk, or a network connection.
protocol Logger {
    
    /// Append a new event to the log. Logger adds all events at the 0 index.
    func appendEvent(_: String)
    
    /// Fetch the list of events that this logger receives.
    var events: [Event] { get }
}

/// CarPlay informs `LoggerDelegate`of logging events.
protocol LoggerDelegate: AnyObject {
    
    /// The logger has received a new event.
    func loggerDidAppendEvent()
}

/// `MemoryLogger` is a type of `Logger` that records events only in-memory for the current life cycle of the app.
class MemoryLogger: Logger {
    
    /// The shared logger.
    static let shared = MemoryLogger()
    
    /// The delegate for recieving events.
    weak var delegate: LoggerDelegate?
    
    /// The events list.
    public private(set) var events: [Event]
    
    /// The logging queue for dispatching events.
    private let loggingQueue: OperationQueue
    
    private init() {
        events = []
        loggingQueue = OperationQueue()
        loggingQueue.maxConcurrentOperationCount = 1
        loggingQueue.name = "Memory Logger Queue"
        loggingQueue.qualityOfService = .userInitiated
    }
    
    /// Tells the delegate that the logger received an event.
    func appendEvent(_ event: String) {
        loggingQueue.addOperation {
            self.events.insert(Event(date: Date(), text: event), at: 0)
            guard let delegate = self.delegate else { return }
            DispatchQueue.main.async {
                delegate.loggerDidAppendEvent()
            }
        }
    }
}
