{{- define "chrepl.name" -}}clickhouse-repl{{- end -}}

{{- define "chrepl.fullname" -}}{{ .Release.Name }}-clickhouse-repl{{- end -}}

{{- define "chrepl.labels" -}}
app.kubernetes.io/name: {{ include "chrepl.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "chrepl.selector" -}}
app.kubernetes.io/name: {{ include "chrepl.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "chrepl.configMapName" -}}
{{- if .Values.config.existingConfigMap -}}{{ .Values.config.existingConfigMap }}{{- else -}}{{ include "chrepl.fullname" . }}-config{{- end -}}
{{- end -}}
