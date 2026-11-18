import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// The app's look for every system surface: Live Activities, widgets, alerts, the watch, Share and Siri.
/// A copy of the Compose theme (composeApp/.../design/Palette.kt, Type.kt, Tokens.kt), so the phone's
/// system surfaces match the app. Change both together.
enum Theme {
    // MARK: Colours

    /// The window behind everything.
    static let background = Color(dark: 0x0A0B0D, light: 0xF6F6F7)
    /// Cards and panels.
    static let surface = Color(dark: 0x111215, light: 0xFFFFFF)
    /// Things lifted above a surface.
    static let raised = Color(dark: 0x16181C, light: 0xFFFFFF)
    /// Wells set into a surface: code, quoted text, fields.
    static let sunken = Color(dark: 0x0D0E10, light: 0xF2F2F4)
    static let border = Color(dark: 0x1F2126, light: 0xE3E4E8, contrast: 0.55)
    static let borderStrong = Color(dark: 0x2C2F36, light: 0xCBCDD3, contrast: 0.7)
    static let text = Color(dark: 0xECEDEF, light: 0x0E0F12, contrast: 1)
    static let textMuted = Color(dark: 0x9A9EA7, light: 0x5B5F68, contrast: 0.6)
    static let textFaint = Color(dark: 0x656973, light: 0x8A8E97, contrast: 0.55)
    /// The one highlight: lime in the dark, ink in the light.
    static let accent = Color(dark: 0xC6F169, light: 0x0E0F12, contrast: 0.2)
    static let onAccent = Color(dark: 0x0B0D06, light: 0xFFFFFF)
    static let accentSoft = Color(dark: 0x1C2410, light: 0xEAF6D3)
    /// "Waiting on you". Used only for questions the owner must answer.
    static let waiting = Color(dark: 0xF5B84A, light: 0xB86E00, contrast: 0.25)
    static let onWaiting = Color(dark: 0x1A1103, light: 0xFFFFFF)
    static let waitingSoft = Color(dark: 0x231B0C, light: 0xFFF4E0)
    /// The hairline round a waiting card.
    static let waitingLine = Color(dark: 0x5A4418, light: 0xEBC98C)
    static let info = Color(dark: 0x7DA9FF, light: 0x2F62D9, contrast: 0.2)
    static let success = Color(dark: 0x5BD69A, light: 0x168454, contrast: 0.2)
    static let error = Color(dark: 0xFF6B6B, light: 0xD12C2C, contrast: 0.2)
    static let online = Color(dark: 0x5BD69A, light: 0x1DA162, contrast: 0.2)
    /// The app icon's blue, for the robot mark where it stands alone.
    static let robot = Color(hex: 0x5AB0FF)
    static let robotTop = Color(hex: 0x52B4FF)
    static let robotBottom = Color(hex: 0x2F76EE)

    /// Agent identity colours. An agent keeps the same one everywhere, here and in the app.
    static let agents: [Color] = [
        Color(dark: 0x7DA9FF, light: 0x2F62D9), Color(dark: 0xC6F169, light: 0x4F8A10),
        Color(dark: 0xF5B84A, light: 0xB86E00), Color(dark: 0xFF8FA3, light: 0xD1456A),
        Color(dark: 0x6EE7D6, light: 0x0F8F80), Color(dark: 0xB794F6, light: 0x7A4FD6),
    ]

    /// The colour of the agent with [id], or of its name when the id isn't known.
    static func agent(_ id: String?) -> Color { agents[hue(id ?? "")] }

    /// The same sum as the app's `hue()` (Mapping.kt), in 32-bit arithmetic as Kotlin does it.
    static func hue(_ id: String) -> Int {
        var acc: Int32 = 7
        for unit in id.utf16 { acc = acc &* 31 &+ Int32(unit) }
        return Int(abs(Int(acc % 6)))
    }

    // MARK: Type

    /// Geist for words, Geist Mono for facts. Every style follows Dynamic Type.
    enum Font {
        /// A screen's big name.
        static let display = sans(22, .semibold, relativeTo: .title2)
        /// A card's headline.
        static let title = sans(16, .semibold, relativeTo: .headline)
        static let body = sans(14.5, .regular, relativeTo: .subheadline)
        static let bodyStrong = sans(14.5, .medium, relativeTo: .subheadline)
        static let label = sans(13, .medium, relativeTo: .footnote)
        /// Section labels, set in capitals with wide tracking (see `overline()`).
        static let overline = mono(11, .medium, relativeTo: .caption2)
        /// Names, times and other small facts.
        static let meta = mono(11.5, .regular, relativeTo: .caption)
        static let code = mono(12.5, .regular, relativeTo: .footnote)

        static func sans(_ size: CGFloat, _ weight: SwiftUI.Font.Weight, relativeTo style: SwiftUI.Font.TextStyle) -> SwiftUI.Font {
            .custom(sansName(weight), size: size, relativeTo: style)
        }

        static func mono(_ size: CGFloat, _ weight: SwiftUI.Font.Weight = .regular, relativeTo style: SwiftUI.Font.TextStyle) -> SwiftUI.Font {
            .custom(weight == .regular ? "GeistMono-Regular" : "GeistMono-Medium", size: size, relativeTo: style)
        }

        /// The same size whatever the text size setting, for the few places with a fixed frame (Dynamic Island compact).
        static func fixedMono(_ size: CGFloat, _ weight: SwiftUI.Font.Weight = .medium) -> SwiftUI.Font {
            .custom(weight == .regular ? "GeistMono-Regular" : "GeistMono-Medium", fixedSize: size)
        }

        private static func sansName(_ weight: SwiftUI.Font.Weight) -> String {
            switch weight {
            case .bold, .heavy, .black: "Geist-Bold"
            case .semibold: "Geist-SemiBold"
            case .medium: "Geist-Medium"
            default: "Geist-Regular"
            }
        }
    }

    // MARK: Spacing and shapes (Tokens.kt)

    enum Space {
        static let xxs: CGFloat = 2
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 24
    }

    enum Radius {
        /// Chips, badges, small buttons.
        static let small: CGFloat = 5
        /// Buttons, inputs, code blocks, pictures.
        static let medium: CGFloat = 7
        /// Cards and panels.
        static let large: CGFloat = 10
    }

    /// Starts briskly and settles softly, as the app's motion does. Nil under Reduce Motion.
    static func motion(reduced: Bool) -> Animation? {
        reduced ? nil : .timingCurve(0.2, 0, 0, 1, duration: 0.22)
    }
}

extension View {
    /// Overline style: mono, capitals, tracked out, as the app's section labels.
    func overline() -> some View {
        font(Theme.Font.overline).textCase(.uppercase).tracking(0.66)
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }

    /// A colour with a dark and a light value. Under Increase Contrast it moves [contrast] of the way
    /// to pure white or black, as the app's high contrast palette does.
    init(dark: UInt32, light: UInt32, contrast: CGFloat = 0) {
        #if os(iOS)
        self.init(uiColor: UIColor { traits in
            let isDark = traits.userInterfaceStyle == .dark
            let base = UIColor(hex: isDark ? dark : light)
            guard contrast > 0, traits.accessibilityContrast == .high else { return base }
            return base.mixed(with: isDark ? .white : .black, by: contrast)
        })
        #else
        // The watch is always dark.
        self.init(hex: dark)
        #endif
    }
}

#if canImport(UIKit)
extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }

    func mixed(with other: UIColor, by amount: CGFloat) -> UIColor {
        var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
        getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
        other.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)
        return UIColor(
            red: r1 + (r2 - r1) * amount,
            green: g1 + (g2 - g1) * amount,
            blue: b1 + (b2 - b1) * amount,
            alpha: a1 + (a2 - a1) * amount
        )
    }
}
#endif

// MARK: - Shared pieces

/// The app's robot, from the app icon: a rounded head, an antenna and two eyes.
/// Drawn as one shape with the eyes cut out, so it works in one colour (lock screen, tinted widgets, the watch face).
struct RobotShape: Shape {
    func path(in rect: CGRect) -> Path {
        // The icon's own coordinates (a 1024 canvas), cropped to the robot.
        let box = CGRect(x: 232, y: 140, width: 560, height: 610)
        let scale = min(rect.width / box.width, rect.height / box.height)
        let dx = rect.midX - box.midX * scale
        let dy = rect.midY - box.midY * scale
        func r(_ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat) -> CGRect {
            CGRect(x: x * scale + dx, y: y * scale + dy, width: w * scale, height: h * scale)
        }
        var body = Path()
        body.addEllipse(in: r(474, 158, 76, 76))
        body.addRect(r(500, 220, 24, 80))
        body.addRoundedRect(in: r(252, 292, 520, 440), cornerSize: CGSize(width: 170 * scale, height: 170 * scale), style: .continuous)
        var eyes = Path()
        eyes.addEllipse(in: r(342, 446, 104, 132))
        eyes.addEllipse(in: r(578, 446, 104, 132))
        // Subtracted, not even-odd filled: the antenna overlaps the head and the ball, and must stay solid.
        return body.normalized().subtracting(eyes)
    }
}

/// The robot in one colour, eyes see-through. Where only an `Image` will do (inline widgets, labels),
/// use the same drawing from the asset catalog: `Image("RobotMark")`.
struct RobotMark: View {
    var color: Color = Theme.robot

    var body: some View {
        RobotShape()
            .fill(color)
            .aspectRatio(560 / 610, contentMode: .fit)
            .accessibilityHidden(true)
    }
}

/// The app icon in small: a blue tile with the white robot.
struct AppMarkTile: View {
    var size: CGFloat = 18

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.26, style: .continuous)
            .fill(LinearGradient(colors: [Theme.robotTop, Theme.robotBottom], startPoint: .top, endPoint: .bottom))
            .overlay(RobotMark(color: .white).padding(size * 0.12))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// An agent's initial in its own colour, as on the app's cards.
struct AgentChip: View {
    let name: String
    let agentId: String?
    var size: CGFloat = 22

    var body: some View {
        let hue = Theme.agent(agentId ?? name)
        Text(String(name.prefix(1)).uppercased())
            .font(Theme.Font.fixedMono(size * 0.5))
            .foregroundStyle(hue)
            .frame(width: size, height: size)
            .background(hue.opacity(0.14), in: RoundedRectangle(cornerRadius: Theme.Radius.small, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.Radius.small, style: .continuous).strokeBorder(hue.opacity(0.45), lineWidth: 1))
            .accessibilityHidden(true)
    }
}

/// A small coloured dot: online, waiting.
struct StatusDot: View {
    let color: Color
    var size: CGFloat = 7

    var body: some View {
        Circle().fill(color).frame(width: size, height: size).accessibilityHidden(true)
    }
}

// MARK: - Buttons and layout

/// The app's answer button: amber and filled for the suggested answer, outlined for the rest.
struct CardButtonStyle: ButtonStyle {
    var prominent: Bool
    var compact = false
    var stretch = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .cardButtonChrome(prominent: prominent, compact: compact, stretch: stretch)
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

extension View {
    /// The answer button's look, for a `Link` as well as a `Button`. An answer is never cut short:
    /// a long one wraps onto more lines rather than ending in an ellipsis.
    func cardButtonChrome(prominent: Bool, compact: Bool = false, stretch: Bool = false) -> some View {
        font(compact ? Theme.Font.label : Theme.Font.bodyStrong)
            .multilineTextAlignment(.center)
            .padding(.horizontal, compact ? 10 : 14)
            .padding(.vertical, 5)
            .frame(maxWidth: stretch ? .infinity : nil)
            .frame(minHeight: compact ? 28 : 34)
            .foregroundStyle(prominent ? Theme.onWaiting : Theme.text)
            .background {
                let shape = RoundedRectangle(cornerRadius: compact ? 6 : Theme.Radius.medium, style: .continuous)
                if prominent {
                    shape.fill(Theme.waiting)
                } else {
                    shape.strokeBorder(Theme.borderStrong, lineWidth: 1)
                }
            }
            .contentShape(Rectangle())
    }
}

/// Lays chips out in rows, wrapping when a row is full.
struct FlowLayout: Layout {
    var spacing: CGFloat

    init(spacing: CGFloat) { self.spacing = spacing }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for (index, size) in zip(row.indices, row.sizes) {
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var sizes: [CGSize] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    /// Each item at its natural width, or the row's width if it is wider, where it wraps its own text.
    private func size(of subview: LayoutSubview, within width: CGFloat) -> CGSize {
        let natural = subview.sizeThatFits(.unspecified)
        guard natural.width > width else { return natural }
        return subview.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }

    private func rows(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = [Row()]
        for index in subviews.indices {
            let size = size(of: subviews[index], within: width)
            let isFirst = rows[rows.count - 1].indices.isEmpty
            if !isFirst, rows[rows.count - 1].width + spacing + size.width > width {
                rows.append(Row())
            }
            let startsRow = rows[rows.count - 1].indices.isEmpty
            rows[rows.count - 1].indices.append(index)
            rows[rows.count - 1].sizes.append(size)
            rows[rows.count - 1].width += startsRow ? size.width : size.width + spacing
            rows[rows.count - 1].height = max(rows[rows.count - 1].height, size.height)
        }
        return rows.filter { !$0.indices.isEmpty }
    }
}

// MARK: - Time left

/// "41 minutes left", for VoiceOver.
func spokenTimeLeft(until expiry: Date?, from now: Date) -> String? {
    guard let expiry, expiry > now else { return nil }
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .full
    formatter.maximumUnitCount = 1
    formatter.allowedUnits = [.day, .hour, .minute]
    return formatter.string(from: now, to: expiry).map { "\($0) left" }
}

/// "41:43 left": the one way time left is written, on every surface. It counts down by itself.
struct TimeLeftText: View {
    let expiry: Date
    let now: Date
    var showsClock = true

    var body: some View {
        HStack(spacing: 4) {
            if showsClock {
                Image(systemName: "clock").font(.system(size: 10, weight: .semibold)).accessibilityHidden(true)
            }
            // A live timer claims all the width it is offered. Sizing it by placeholder digits in the same
            // font keeps it to its own width at every text size.
            Text(placeholder)
                .hidden()
                .overlay(alignment: .trailing) {
                    Text(timerInterval: now...expiry, countsDown: true, showsHours: true)
                        .monospacedDigit()
                        .multilineTextAlignment(.trailing)
                }
            Text("left")
        }
        .lineLimit(1)
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(spokenTimeLeft(until: expiry, from: now) ?? "")
    }

    private var placeholder: String {
        let seconds = expiry.timeIntervalSince(now)
        return seconds >= 36000 ? "00:00:00" : seconds >= 3600 ? "0:00:00" : "00:00"
    }
}
