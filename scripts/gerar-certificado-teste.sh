#!/usr/bin/env bash
#
# Gera uma AC raiz de TESTE e um certificado de assinatura do servico (.p12).
#
# Uso:  scripts/gerar-certificado-teste.sh [pasta-de-saida] [senha]
#   pasta-de-saida  padrao: certs
#   senha do .p12   padrao: a variavel SIGNING_CERT_PASSWORD; se ausente, o
#                   script pede ao usuario (a senha nao e impressa).
#
# Saidas (na pasta de saida):
#   ac-teste.crt        certificado publico da AC de teste (importar no leitor
#                       de PDF para o selo aparecer como confiavel em demos)
#   ac-teste.key        chave privada da AC de teste        (SEGREDO)
#   servico.p12         certificado + chave + cadeia do servico (SEGREDO)
#
# Para usar um certificado A1 real em producao, basta apontar
# SIGNING_CERT_PATH/SIGNING_CERT_PASSWORD para o .p12 real: nenhum codigo muda.
#
# ATENCAO: estes certificados sao SOMENTE para teste. A pasta "certs/" e os
# arquivos *.p12/*.key estao no .gitignore e NAO devem ser versionados.

set -euo pipefail

SAIDA="${1:-certs}"
SENHA="${2:-${SIGNING_CERT_PASSWORD:-}}"

if [ -z "$SENHA" ]; then
  read -r -s -p "Senha para o servico.p12: " SENHA
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

# Senha passada por variavel de ambiente (nao aparece na lista de processos).
export SICA_P12_PASS="$SENHA"

# --- AC raiz de teste (valida por 10 anos) ---
openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
  -keyout "$SAIDA/ac-teste.key" -out "$SAIDA/ac-teste.crt" \
  -subj "/C=BR/O=SiCA Teste/CN=SiCA AC Raiz de Teste" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign"
chmod 600 "$SAIDA/ac-teste.key"

# --- Certificado do servico (valido por 2 anos), assinado pela AC de teste ---
openssl req -newkey rsa:3072 -nodes -sha256 \
  -keyout "$TMP/servico.key" -out "$TMP/servico.csr" \
  -subj "/C=BR/O=SiCA Teste/CN=SiCA Servico de Assinatura (TESTE)"

cat > "$TMP/servico.ext" <<'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,nonRepudiation
extendedKeyUsage=emailProtection,1.2.840.113583.1.1.5
subjectKeyIdentifier=hash
authorityKeyIdentifier=keyid
EOF

openssl x509 -req -sha256 -days 730 \
  -in "$TMP/servico.csr" \
  -CA "$SAIDA/ac-teste.crt" -CAkey "$SAIDA/ac-teste.key" -CAcreateserial \
  -CAserial "$TMP/ac.srl" \
  -extfile "$TMP/servico.ext" -out "$TMP/servico.crt"

# --- Empacota em PKCS#12 (chave + certificado + cadeia) ---
openssl pkcs12 -export \
  -inkey "$TMP/servico.key" -in "$TMP/servico.crt" -certfile "$SAIDA/ac-teste.crt" \
  -name "sica-servico" -passout env:SICA_P12_PASS \
  -out "$SAIDA/servico.p12"
chmod 600 "$SAIDA/servico.p12"

unset SICA_P12_PASS

echo "Certificados de TESTE gerados em: $SAIDA"
echo "  AC de teste (publica): $SAIDA/ac-teste.crt"
echo "  Certificado do servico: $SAIDA/servico.p12"
echo
echo "Configure:"
echo "  export SIGNING_CERT_PATH=\"$SAIDA/servico.p12\""
echo "  export SIGNING_CERT_PASSWORD=<a senha informada>"
