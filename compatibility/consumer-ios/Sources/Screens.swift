import SignalDock
import SwiftUI

struct RootView: View {
  var body: some View {
    TabView {
      ShopView().tabItem { Label("Shop", systemImage: "bag") }
      CartView().tabItem { Label("Cart", systemImage: "cart") }
      DiagnosticsView().tabItem { Label("Diagnostics", systemImage: "stethoscope") }
    }
  }
}

struct ShopView: View {
  var body: some View {
    NavigationView {
      List(Product.catalog) { product in
        NavigationLink(destination: ProductView(product: product)) {
          HStack {
            Text(product.name)
            Spacer()
            Text(money(product.priceMinor))
          }
        }
      }
      .navigationTitle("Shop")
    }
    .navigationViewStyle(.stack)
  }
}

struct ProductView: View {
  @EnvironmentObject var model: AppModel
  let product: Product

  var body: some View {
    VStack(spacing: 16) {
      Text(product.name).font(.title)
      Text(money(product.priceMinor))
      Button("Add to cart") { Task { await model.add(product) } }
        .buttonStyle(.borderedProminent)
      Text(model.message).font(.footnote).foregroundColor(.secondary)
    }
    .padding()
    .navigationTitle(product.name)
    .task { await model.view(product) }
  }
}

struct CartView: View {
  @EnvironmentObject var model: AppModel

  var body: some View {
    NavigationView {
      VStack(spacing: 12) {
        List(model.cart.sorted { $0.key.id < $1.key.id }, id: \.key.id) { line in
          HStack {
            Text("\(line.value) x \(line.key.name)")
            Spacer()
            Text(money(line.key.priceMinor * Int64(line.value)))
          }
        }
        Text("Total: \(money(model.totalMinor))")
        HStack {
          Button("Start checkout") { Task { await model.startCheckout() } }
          Button("Purchase (success)") { Task { await model.purchase(succeed: true) } }
          Button("Purchase (failure)") { Task { await model.purchase(succeed: false) } }
        }
        .buttonStyle(.bordered)
        .font(.footnote)
        Text(model.message).font(.footnote).foregroundColor(.secondary).padding(.horizontal)
      }
      .navigationTitle("Cart")
    }
    .navigationViewStyle(.stack)
  }
}

struct DiagnosticsView: View {
  @EnvironmentObject var model: AppModel
  @State private var text = "Tap Refresh."

  var body: some View {
    NavigationView {
      VStack(alignment: .leading, spacing: 12) {
        Text("Endpoint: \(Settings.endpoint)").font(.footnote)
        Button("Refresh") { Task { await refresh() } }.buttonStyle(.bordered)
        ScrollView { Text(text).font(.system(.footnote, design: .monospaced)) }
        Spacer()
      }
      .padding()
      .navigationTitle("Diagnostics")
      .task { await refresh() }
    }
    .navigationViewStyle(.stack)
  }

  private func refresh() async {
    switch model.state {
    case .starting: text = "SDK starting. Tap Refresh."
    case .failed(let error): text = "init failed: \(error)"
    case .ready(let sdk):
      if let d = try? await sdk.diagnostics() {
        text = "\(d)".replacingOccurrences(of: ", ", with: ",\n  ")
      } else {
        text = "diagnostics cancelled"
      }
    }
  }
}
