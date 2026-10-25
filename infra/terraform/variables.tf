variable "project_id" {
  description = "GCP project that holds the demo."
  type        = string
  default     = "signaldock-demo"
}

variable "region" {
  type    = string
  default = "asia-northeast3"
}

variable "zone" {
  type    = string
  default = "asia-northeast3-a"
}

variable "cloudflare_zone_id" {
  description = "Zone ID of stack-line.dev (Cloudflare dashboard, zone Overview). Not a secret. The API token is not a variable: the provider reads CLOUDFLARE_API_TOKEN."
  type        = string
}

variable "record_name" {
  description = "Host label under the zone: signaldock gives signaldock.stack-line.dev."
  type        = string
  default     = "signaldock"
}
