{{- define "orders.fullname" -}}
  {{- if .Values.fullnameOverride -}}
    {{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
  {{- else -}}
    {{- printf "%s-%s" .Release.Name .Chart.Name -}}
  {{- end -}}
{{- end -}}

{{- define "orders.labels" }}
{{- range $key, $value := .Values.labels }}
{{ $key }}: {{ $value | quote }}
{{- end }}
heritage: {{ .Release.Service | quote }}
release: {{ .Release.Name | quote }}
chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{/* Orders Environment Variables */}}
{{- define "orders.environmentvariables" }}
- name: SERVICE_PORT
  value: {{ .Values.service.internalPort | quote }}
- name: JAVA_TMP_DIR
  value: /spring-tmp
{{- end }}

{{/* MySQL Init Container Template */}}
{{- define "orders.mariadb.initcontainer" }}
{{- if not (or .Values.global.istio.enabled .Values.istio.enabled) }}
- name: test-mariadb
  image: {{ .Values.mysql.image }}:{{ .Values.mysql.imageTag }}
  imagePullPolicy: {{ .Values.mysql.imagePullPolicy }}
  command:
  - "/bin/bash"
  - "-c"
  {{- if .Values.mariadb.password }}
  - "until mysql -h ${MYSQL_HOST} -P ${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASSWORD} -e status; do echo waiting for mariadb; sleep 1; done"
  {{- else }}
  - "until mysql -h ${MYSQL_HOST} -P ${MYSQL_PORT} -u${MYSQL_USER} -e status; do echo waiting for mariadb; sleep 1; done"
  {{- end }}
  resources:
  {{- include "orders.resources" . | indent 4 }}
  securityContext:
  {{- include "orders.securityContext" . | indent 4 }}
  env:
  {{- include "orders.mariadb.environmentvariables" . | indent 2 }}
{{- end }}
{{- end }}

{{/* Orders MySQL Environment Variables */}}
{{- define "orders.mariadb.environmentvariables" }}
- name: MYSQL_HOST
  value: {{ template "orders.mariadb.host" . }}
- name: MYSQL_PORT
  value: {{ .Values.mariadb.port | quote }}
- name: MYSQL_DATABASE
  value: {{ .Values.mariadb.database | quote }}
- name: MYSQL_USER
  value: {{ .Values.mariadb.user | quote }}
{{- if or .Values.mariadb.password .Values.mariadb.existingSecret }}
- name: MYSQL_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ template "orders.mariadb.secretName" . }}
      key: mariadb-password
{{- end }}
{{- end }}

{{/* MariaDB Host */}}
{{- define "orders.mariadb.host" }}
  {{- if .Values.mariadb.host }}
    {{- .Values.mariadb.host }}
  {{- else -}}
    {{/* Assume orders-mariadb as nameOverride for MariaDB */}}
    {{- .Release.Name }}-orders-mariadb
  {{- end }}
{{- end }}

{{/* MariaDB Secret Name */}}
{{- define "orders.mariadb.secretName" }}
  {{- if .Values.mariadb.existingSecret }}
    {{- .Values.mariadb.existingSecret }}
  {{- else -}}
    {{ template "orders.fullname" . }}-mariadb-secret
  {{- end }}
{{- end }}

{{/* Orders HS256KEY Environment Variables */}}
{{- define "orders.hs256key.environmentvariables" }}
- name: HS256_KEY
  valueFrom:
    secretKeyRef:
      name: {{ template "orders.hs256key.secretName" . }}
      key:  key
{{- end }}

{{/* Orders HS256KEY Secret Name */}}
{{- define "orders.hs256key.secretName" -}}
  {{- if .Values.global.hs256key.secretName -}}
    {{ .Values.global.hs256key.secretName -}}
  {{- else if .Values.hs256key.secretName -}}
    {{ .Values.hs256key.secretName -}}
  {{- else -}}
    {{- .Release.Name }}-{{ .Chart.Name }}-hs256key
  {{- end }}
{{- end -}}

{{/* Orders Resources */}}
{{- define "orders.resources" }}
limits:
  memory: {{ .Values.resources.limits.memory }}
requests:
  memory: {{ .Values.resources.requests.memory }}
{{- end }}

{{/* Orders Security Context */}}
{{- define "orders.securityContext" }}
{{- range $key, $value := .Values.securityContext }}
{{ $key }}: {{ $value }}
{{- end }}
{{- end }}

{{/* Istio Gateway */}}
{{- define "orders.istio.gateway" }}
  {{- if or .Values.global.istio.gateway.name .Values.istio.gateway.enabled .Values.istio.gateway.name }}
  gateways:
  {{ if .Values.global.istio.gateway.name -}}
  - {{ .Values.global.istio.gateway.name }}
  {{- else if .Values.istio.gateway.enabled }}
  - {{ template "orders.fullname" . }}-gateway
  {{ else if .Values.istio.gateway.name -}}
  - {{ .Values.istio.gateway.name }}
  {{ end }}
  {{- end }}
{{- end }}