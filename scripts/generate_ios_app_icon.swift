import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Rasterize the geometry and colors from brand/mark.svg for Apple's app icon slot.
let size = 1024
let colorSpace = CGColorSpaceCreateDeviceRGB()
guard let context = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: 0, space: colorSpace,
    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
) else { fatalError("Could not create icon bitmap") }

func color(_ red: CGFloat, _ green: CGFloat, _ blue: CGFloat) -> CGColor {
    CGColor(colorSpace: colorSpace, components: [red / 255, green / 255, blue / 255, 1])!
}

let charcoal = color(14, 23, 28)
let mint = color(162, 227, 208)
let amber = color(239, 210, 154)
context.setFillColor(charcoal)
context.fill(CGRect(x: 0, y: 0, width: size, height: size))

// Keep the original 64-point mark centered with space for iOS icon masking.
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: 1, y: -1)
context.translateBy(x: 112, y: 112)
context.scaleBy(x: 12.5, y: 12.5)

let frame = CGMutablePath()
frame.move(to: CGPoint(x: 42, y: 13))
frame.addLine(to: CGPoint(x: 16, y: 13))
frame.addCurve(to: CGPoint(x: 8, y: 21), control1: CGPoint(x: 11.6, y: 13), control2: CGPoint(x: 8, y: 16.6))
frame.addLine(to: CGPoint(x: 8, y: 39))
frame.addCurve(to: CGPoint(x: 16, y: 47), control1: CGPoint(x: 8, y: 43.4), control2: CGPoint(x: 11.6, y: 47))
frame.addLine(to: CGPoint(x: 48, y: 47))
frame.addCurve(to: CGPoint(x: 56, y: 39), control1: CGPoint(x: 52.4, y: 47), control2: CGPoint(x: 56, y: 43.4))
frame.addLine(to: CGPoint(x: 56, y: 27))
frame.move(to: CGPoint(x: 24, y: 55))
frame.addLine(to: CGPoint(x: 40, y: 55))
context.addPath(frame)
context.setStrokeColor(mint)
context.setLineWidth(4)
context.setLineCap(.round)
context.setLineJoin(.round)
context.strokePath()
context.setFillColor(amber)
context.fillEllipse(in: CGRect(x: 47, y: 7, width: 14, height: 14))

guard let image = context.makeImage() else { fatalError("Could not finish icon bitmap") }
let output = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent()
    .appendingPathComponent("ios/TVCompanion/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
guard let destination = CGImageDestinationCreateWithURL(output as CFURL, UTType.png.identifier as CFString, 1, nil) else {
    fatalError("Could not write \(output.path)")
}
CGImageDestinationAddImage(destination, image, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("Could not save icon PNG") }
print(output.path)
