import SharedLogic
import SwiftUI

extension CategoryIcon {
    /// The shared icon names as SF Symbols; Material's on Android.
    var systemName: String {
        switch self {
        case .home: "house.fill"
        case .transfer: "arrow.left.arrow.right"
        case .document: "doc.text.fill"
        case .car: "car.fill"
        case .bag: "bag.fill"
        case .bolt: "bolt.fill"
        case .dining: "fork.knife"
        case .cart: "cart.fill"
        case .heart: "cross.case.fill"
        case .salary: "banknote.fill"
        case .shield: "shield.fill"
        case .school: "graduationcap.fill"
        case .ticket: "ticket.fill"
        case .gift: "gift.fill"
        case .spa: "leaf.fill"
        case .piggy: "dollarsign.circle.fill"
        case .repeat: "repeat"
        case .plane: "airplane"
        case .card: "creditcard.fill"
        case .phone: "iphone"
        case .tag: "tag.fill"
        case .unfiled: "questionmark.circle.fill"
        }
    }

    /**
     A colour per kind of spending, for the tile behind a category's icon: the
     home, the table, getting about, looking after yourself, money coming in,
     going out, and paperwork. Kinds share a colour so the list stays calm, and
     an unfiled row is amber — the one that needs somebody. Mirrors Android's
     `CategoryIcon.tint`.
     */
    func tint(_ dark: Bool) -> Color {
        let (light, night): (UInt32, UInt32) = switch self {
        case .home, .bolt, .phone, .repeat: (0x2563EB, 0x60A5FA)
        case .dining, .cart, .bag: (0xEA580C, 0xFB923C)
        case .car, .plane, .transfer: (0x0D9488, 0x2DD4BF)
        case .heart, .spa, .shield: (0xDB2777, 0xF472B6)
        case .salary, .piggy: (0x15803D, 0x4ADE80)
        case .ticket, .gift, .school: (0x7C3AED, 0xA78BFA)
        case .document, .card, .tag: (0x475569, 0x94A3B8)
        case .unfiled: (0xB45309, 0xFBBF24)
        }
        let hex = dark ? night : light
        return Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}
