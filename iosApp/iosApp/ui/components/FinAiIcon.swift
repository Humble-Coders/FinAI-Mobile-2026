import SharedLogic
import SwiftUI

/**
 One of the shared `Vectors`, drawn at a given size.

 The geometry is shared with Android so the two apps draw the same shape; this
 only turns it into a SwiftUI `Path`. SF Symbols were the alternative and would
 have given iOS a different wallet, a different bell and a different piggy bank
 from Android's — the same idea in two shapes, which is what makes two apps
 look like two products.

 Even-odd filling, so a hole punched by an inner subpath — the clasp on the
 wallet, the pupil in the eye — is a hole and not a second blob on top.
 */
struct FinAiIcon: View {
    let icon: IconPath
    let tint: Color
    var size: CGFloat = 20
    /// Nil for an icon that repeats a label beside it, which is most of them:
    /// VoiceOver should hear the row once.
    var label: String?

    var body: some View {
        IconShape(icon: icon)
            .fill(tint, style: FillStyle(eoFill: true))
            .frame(width: size, height: size)
            .modifier(IconAccessibility(label: label))
    }
}

/// The shared commands as a SwiftUI path, scaled into whatever rect it is given.
struct IconShape: Shape {
    let icon: IconPath

    func path(in rect: CGRect) -> Path {
        var path = Path()
        // The box is authored at 24; one factor, so the shape never distorts.
        let factor = min(rect.width, rect.height) / CGFloat(icon.viewport)
        func point(_ x: Float, _ y: Float) -> CGPoint {
            CGPoint(x: CGFloat(x) * factor, y: CGFloat(y) * factor)
        }
        for command in icon.commands {
            switch onEnum(of: command) {
            case .moveTo(let move):
                path.move(to: point(move.x, move.y))
            case .lineTo(let line):
                path.addLine(to: point(line.x, line.y))
            case .curveTo(let curve):
                path.addCurve(
                    to: point(curve.x, curve.y),
                    control1: point(curve.x1, curve.y1),
                    control2: point(curve.x2, curve.y2)
                )
            case .close:
                path.closeSubpath()
            }
        }
        return path
    }
}

/// Hidden when it has no label of its own, rather than announced as an image.
private struct IconAccessibility: ViewModifier {
    let label: String?

    func body(content: Content) -> some View {
        if let label {
            content.accessibilityLabel(label)
        } else {
            content.accessibilityHidden(true)
        }
    }
}
