# Cloud demo VM (ADR-0043 items 1 and 2). Local state. No secret value in this directory.

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

# Token from the CLOUDFLARE_API_TOKEN environment variable.
provider "cloudflare" {}

locals {
  vm_tag = "signaldock-demo"
}

# A dedicated network. The project's "default" network has default-allow-ssh and
# default-allow-rdp open to 0.0.0.0/0, which would defeat the IAP-only rule below.
resource "google_compute_network" "demo" {
  name                    = "signaldock-demo"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "demo" {
  name          = "signaldock-demo"
  network       = google_compute_network.demo.id
  region        = var.region
  ip_cidr_range = "10.10.0.0/24"
}

resource "google_compute_address" "demo" {
  name         = "signaldock-demo"
  region       = var.region
  address_type = "EXTERNAL"
}

resource "google_compute_firewall" "web" {
  name          = "signaldock-demo-web"
  network       = google_compute_network.demo.name
  source_ranges = ["0.0.0.0/0"]
  target_tags   = [local.vm_tag]

  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

# 35.235.240.0/20 is the IAP TCP forwarding range.
resource "google_compute_firewall" "iap_ssh" {
  name          = "signaldock-demo-iap-ssh"
  network       = google_compute_network.demo.name
  source_ranges = ["35.235.240.0/20"]
  target_tags   = [local.vm_tag]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_instance" "demo" {
  name         = "signaldock-demo"
  machine_type = "e2-medium"
  tags         = [local.vm_tag]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = 30
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.demo.id

    access_config {
      nat_ip = google_compute_address.demo.address
    }
  }

  # No service_account block: the VM has no service account (ADR-0043 item 2, spec D6).
  # user-data is public repo setup only. Keys and htpasswd are copied by the owner (runbook).
  metadata = {
    user-data = file("${path.module}/cloud-init.yaml")
  }
}

# Proxy off: host nginx must see the client IP for the per-IP limits (ADR-0043 item 3).
resource "cloudflare_dns_record" "demo" {
  zone_id = var.cloudflare_zone_id
  name    = var.record_name
  type    = "A"
  content = google_compute_address.demo.address
  ttl     = 300
  proxied = false
}
