#!/usr/bin/env bash
#
# Gera o certificado HTTPS (TLS) de TESTE do gateway, em PKCS#12.
#
# Uso:  scripts/gerar-certificado-https.sh [pasta-de-saida] [senha] [host-extra ...]
#   pasta-de-saida  padrao: certs
#   senha           padrao: a variavel HTTPS_KEYSTORE_PASSWORD; se ausente, pede ao usuario
#   host-extra      nomes ou IPs adicionais (ex.: o IP da maquina na rede local,
#                   para abrir o link pelo celular). localhost, 127.0.0.1 e ::1
#                   ja estao incluidos.
#
# Se a pasta de saida ja tiver a AC de teste (ac-teste.crt/ac-teste.key, criada
# por scripts/gerar-certificado-teste.sh), o certificado e emitido por ela: basta
# importar ac-teste.crt como confiavel uma vez para o navegador aceitar o site
# E o selo dos PDFs. Sem a AC, gera um certificado autoassinado.
#
# Saida: <pasta>/https.p12 (SEGREDO; certs/ e *.p12 estao no .gitignore)
#
# Alternativa sem aviso no navegador: mkcert (https://github.com/FiloSottile/mkcert)
#   mkcert -install && mkcert -pkcs12 localhost 127.0.0.1
#   -> localhost+1.p12, senha "changeit"

set -euo pipefail

SAIDA="${1:-certs}"
SENHA="${2:-${HTTPS_KEYSTORE_PASSWORD:-}}"
shift $(( $# > 2 ? 2 : $# )) || true

if [ -z "$SENHA" ]; then
  read -r -s -p "Senha para o https.p12: " SENHA
  echo
fi
if [ -z "$SENHA" ]; then
  echo "Erro: senha vazia." >&2
  exit 1
fi
command -v openssl >/dev/null 2>&1 || { echo "Erro: openssl nao encontrado." >&2; exit 1; }

mkdir -p "$SAIDA"
chmod 700 "$SAIDA"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
export ANGEL_P12_PASS="$SENHA"

# Nomes alternativos (SAN): obrigatorios para os navegadores atuais.
SAN="DNS:localhost,IP:127.0.0.1,IP:::1"
for h in "$@"; do
  if [[ "$h" =~ ^[0-9.]+$ || "$h" == *:* ]]; then
    SAN="$SAN,IP:$h"
  else
    SAN="$SAN,DNS:$h"
  fi
done

cat > "$TMP/https.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$SAN
subjectKeyIdentifier=hash
EOF

openssl req -newkey rsa:2048 -nodes -sha256 \
  -keyout "$TMP/https.key" -out "$TMP/https.csr" \
  -subj "/C=BR/O=Angel Signature (TESTE)/CN=localhost"

# Validade de 397 dias: limite aceito pelos navegadores para certificados TLS.
if [ -f "$SAIDA/ac-teste.crt" ] && [ -f "$SAIDA/ac-teste.key" ]; then
  echo "authorityKeyIdentifier=keyid" >> "$TMP/https.ext"
  openssl x509 -req -sha256 -days 397 -in "$TMP/https.csr" \
    -CA "$SAIDA/ac-teste.crt" -CAkey "$SAIDA/ac-teste.key" \
    -CAcreateserial -CAserial "$TMP/ac.srl" \
    -extfile "$TMP/https.ext" -out "$TMP/https.crt"
  CADEIA=(-certfile "$SAIDA/ac-teste.crt")
  EMISSOR="AC de teste ($SAIDA/ac-teste.crt)"
else
  openssl x509 -req -sha256 -days 397 -in "$TMP/https.csr" -signkey "$TMP/https.key" \
    -extfile "$TMP/https.ext" -out "$TMP/https.crt"
  CADEIA=()
  EMISSOR="autoassinado"
fi

openssl pkcs12 -export -inkey "$TMP/https.key" -in "$TMP/https.crt" ${CADEIA[@]+"${CADEIA[@]}"} \
  -name "angel-signature-https" -passout env:ANGEL_P12_PASS -out "$SAIDA/https.p12"
chmod 600 "$SAIDA/https.p12"
unset ANGEL_P12_PASS

echo "Certificado HTTPS de TESTE gerado: $SAIDA/https.p12"
echo "  Emissor: $EMISSOR"
echo "  Nomes:   $SAN"
echo
echo "Configure:"
echo "  export HTTPS_KEYSTORE_PATH=\"$SAIDA/https.p12\""
echo "  export HTTPS_KEYSTORE_PASSWORD=<a senha informada>"
echo "  export BASE_URL=\"https://localhost:8080\""
