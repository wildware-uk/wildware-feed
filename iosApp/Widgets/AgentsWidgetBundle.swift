import SwiftUI
import WidgetKit

@main
struct AgentsWidgetBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
        ProjectWidget()
        AgentsLiveActivity()
        VoiceNoteControl()
        VoiceNoteLiveActivity()
    }
}
