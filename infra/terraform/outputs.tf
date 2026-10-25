output "ip" {
  value = google_compute_address.demo.address
}

output "url" {
  value = "https://${local.fqdn}"
}

output "ssh" {
  value = "gcloud compute ssh ${google_compute_instance.demo.name} --project ${var.project_id} --zone ${var.zone} --tunnel-through-iap"
}
