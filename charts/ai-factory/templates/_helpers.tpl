{{- define "ai-factory.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "ai-factory.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "ai-factory.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "ai-factory.labels" -}}
app.kubernetes.io/name: {{ include "ai-factory.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "ai-factory.selectorLabels" -}}
app.kubernetes.io/name: {{ include "ai-factory.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
