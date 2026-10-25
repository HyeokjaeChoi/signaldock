output "ip" {
  value = google_compute_address.demo.address
}

output "url" {
  value = "https://${var.record_name}.stack-line.dev"
}

output "ssh" {
  value = "gcloud compute ssh ${google_compute_instance.demo.name} --project ${var.project_id} --zone ${var.zone} --tunnel-through-iap"
}
