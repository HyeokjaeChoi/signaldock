import Foundation

struct Product: Hashable, Identifiable {
  let id: String
  let name: String
  let priceMinor: Int64

  static let catalog = [
    Product(id: "sku-mug", name: "Enamel mug", priceMinor: 1_200),
    Product(id: "sku-tote", name: "Canvas tote", priceMinor: 2_400),
    Product(id: "sku-notebook", name: "Dot notebook", priceMinor: 800),
  ]
}

func money(_ minor: Int64) -> String { String(format: "%d.%02d", Int(minor / 100), Int(minor % 100)) }
