#!/usr/bin/env bash
#
# Inicia o sistema inteiro (servidor TCP do SiCA + gateway web) com um comando.
#
# Uso:  scripts/iniciar.sh
#
# Na primeira execucao ele:
#   1. cria o .env a partir do .env.example, gerando ADMIN_KEY e as senhas;
#   2. gera os certificados de teste em certs/ (precisa do OpenSSL);
#   3. compila o projeto.
# Depois sobe os dois servidores. Ctrl+C encerra os dois.
#
# Portas (opcional): SICA_PORTA (TCP, padrao 5050) e HTTP_PORTA (web, padrao 8080).
# Se mudar HTTP_PORTA, ajuste tambem BASE_URL no .env.

set -euo pipefail

cd "$(dirname "$0")/.."

SICA_PORTA="${SICA_PORTA:-5050}"
HTTP_PORTA="${HTTP_PORTA:-8080}"

erro() { echo "Erro: $*" >&2; exit 1; }

for cmd in java mvn openssl; do
  command -v "$cmd" >/dev/null 2>&1 || erro "'$cmd' nao encontrado. Instale JDK 17+, Maven e OpenSSL."
done

# --- 1. .env -----------------------------------------------------------------
# Troca o valor de uma variavel no .env (portavel entre macOS e Linux).
definir() {
  local chave="$1" valor="$2"
  sed -i.bak "s|^export ${chave}=.*|export ${chave}=\"${valor}\"|" .env && rm -f .env.bak
}

if [ ! -f .env ]; then
  [ -f .env.example ] || erro ".env.example nao encontrado."
  cp .env.example .env
  chmod 600 .env
  SENHA="$(openssl rand -hex 12)"
  definir ADMIN_KEY "$(openssl rand -hex 16)"
  definir SIGNING_CERT_PASSWORD "$SENHA"
  definir HTTPS_KEYSTORE_PASSWORD "$SENHA"
  echo "Criei o .env com ADMIN_KEY e senhas geradas."
fi

# shellcheck disable=SC1091
source .env

[ -n "${ADMIN_KEY:-}" ] && [ "$ADMIN_KEY" != "troque-por-uma-chave-forte" ] \
  || erro "Defina ADMIN_KEY no .env (e a chave de acesso ao painel)."
[ -n "${SIGNING_CERT_PASSWORD:-}" ] || erro "Defina SIGNING_CERT_PASSWORD no .env."
[ -n "${HTTPS_KEYSTORE_PASSWORD:-}" ] || erro "Defina HTTPS_KEYSTORE_PASSWORD no .env."

# --- 2. Certificados ---------------------------------------------------------
if [ ! -f certs/servico.p12 ] || [ ! -f certs/ac-teste.crt ]; then
  echo "Gerando certificado de teste do selo PAdES..."
  scripts/gerar-certificado-teste.sh certs "$SIGNING_CERT_PASSWORD" >/dev/null
fi
if [ ! -f certs/https.p12 ]; then
  echo "Gerando certificado HTTPS/TLS..."
  scripts/gerar-certificado-https.sh certs "$HTTPS_KEYSTORE_PASSWORD" >/dev/null
fi

# --- 3. Compilar -------------------------------------------------------------
echo "Compilando..."
mvn -q compile

# --- 4. Subir ---------------------------------------------------------------
PID_TCP=""
encerrar() {
  trap - INT TERM EXIT
  echo
  echo "Encerrando..."
  [ -n "$PID_TCP" ] && kill "$PID_TCP" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap encerrar INT TERM EXIT

mvn -q exec:java -Dexec.mainClass=sica.server.Servidor -Dexec.args="$SICA_PORTA" &
PID_TCP=$!

# Espera o servidor TCP abrir a porta (ate 30 s).
for _ in $(seq 1 60); do
  if (exec 3<>"/dev/tcp/127.0.0.1/$SICA_PORTA") 2>/dev/null; then break; fi
  kill -0 "$PID_TCP" 2>/dev/null || erro "O servidor TCP nao subiu (porta $SICA_PORTA em uso?)."
  sleep 0.5
done

echo
echo "Painel:  https://localhost:$HTTP_PORTA"
echo "Chave de acesso (ADMIN_KEY): esta no arquivo .env"
echo "Ctrl+C para encerrar."
echo

mvn -q exec:java -Dexec.mainClass=sica.web.GatewayWeb \
  -Dexec.args="$HTTP_PORTA localhost $SICA_PORTA"
