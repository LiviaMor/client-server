# Ambiente e arquitetura — Angel Signature

> Referência técnica para quem vai configurar, operar ou evoluir o sistema.
> Para uso e visão geral, veja o [README](../README.md); para o histórico das etapas e
> decisões, o [PLANO-ASSINATURA.md](PLANO-ASSINATURA.md).

## Sumário

- [Parte 1 — Variáveis de ambiente](#parte-1--variáveis-de-ambiente)
- [Parte 2 — Padrão de arquitetura](#parte-2--padrão-de-arquitetura)

---

# Parte 1 — Variáveis de ambiente

## 1.1 Como a configuração é lida

- Toda a configuração do módulo de assinatura vem de **variáveis de ambiente**, lidas **uma
  única vez na inicialização** pela classe `sica.assinatura.config.Configuracao`.
  Alterou o `.env`? Rode `source .env` e **reinicie o gateway**.
- Valor vazio ou só com espaços conta como **ausente** (usa o padrão).
- Valor inválido (número não positivo, `BASE_URL` sem `http(s)://`, `EMAIL_PROVIDER`
  desconhecido) **impede a subida**, com mensagem clara. Não há "meia configuração".
- Segredos (`ADMIN_KEY`, senhas de certificado, `RESEND_API_KEY`) nunca ficam no código nem
  no repositório. O modelo é o [`.env.example`](../.env.example); o `.env` real está no
  `.gitignore`.

```bash
cp .env.example .env     # preencha os valores
source .env              # carrega no terminal atual
mvn exec:java -Dexec.mainClass=sica.web.GatewayWeb -Dexec.args="8080 localhost 5050"
```

## 1.2 Referência

**Sensível** = nunca versionar, nunca registrar em log, nunca colar em conversas.

### Acesso e rede

| Variável | Obrig. | Padrão | Sensível | Descrição |
|---|---|---|---|---|
| `ADMIN_KEY` | Sim* | — | **Sim** | Chave do emissor. Exigida (`X-Admin-Key`) em `/envelopes/*` e `/api/*`. Compara em tempo constante. |
| `BASE_URL` | Não | `http://localhost:8080` | Não | URL pública usada nos **links dos e-mails**. Sem barra final; deve começar com `http://` ou `https://`. |
| `HTTPS_KEYSTORE_PATH` | Não | — | Não | Keystore PKCS#12 do TLS. **Sem ele o gateway sobe em HTTP simples** (só desenvolvimento). |
| `HTTPS_KEYSTORE_PASSWORD` | Se houver keystore | — | **Sim** | Senha do keystore HTTPS. Keystore informado e inválido **impede a subida** (não cai para HTTP). |

\* Sem `ADMIN_KEY`, o módulo de assinatura **não sobe** (aviso no log) e as rotas `/api/*` do
SiCA ficam **abertas**, como no projeto original. Em qualquer ambiente acessível por outras
pessoas, defina a chave.

### E-mail

| Variável | Obrig. | Padrão | Sensível | Descrição |
|---|---|---|---|---|
| `EMAIL_PROVIDER` | Não | `console` | Não | `console` imprime a mensagem (com o link) no terminal e **não envia nada**; `resend` envia pela API do Resend. |
| `RESEND_API_KEY` | Se `resend` | — | **Sim** | Chave `re_...` do Resend. Sem ela, `EMAIL_PROVIDER=resend` impede a subida. |
| `EMAIL_FROM` | Não | `Angel Signature <onboarding@resend.dev>` | Não | Remetente. Com o remetente `resend.dev`, o Resend só entrega ao e-mail da própria conta; para outros destinatários, verifique um domínio. |

### Selo digital e carimbo de tempo

| Variável | Obrig. | Padrão | Sensível | Descrição |
|---|---|---|---|---|
| `SIGNING_CERT_PATH` | Não* | — | Não | `.p12`/`.pfx` do certificado do serviço (selo PAdES). |
| `SIGNING_CERT_PASSWORD` | Se houver certificado | — | **Sim** | Senha do `.p12`. |
| `TSA_URL` | Não | `https://freetsa.org/tsr` | Não | Servidor de carimbo de tempo RFC 3161. `none` desativa (**só desenvolvimento**: o selo fica sem prova de tempo). |

\* Sem `SIGNING_CERT_PATH` o sistema sobe, mas a **finalização fica desativada** (as assinaturas
são aceitas e o PDF final não é gerado). Certificado informado e inválido **impede a subida**.
Trocar o certificado de teste por um A1 real é só configuração.

### Armazenamento e limites

| Variável | Obrig. | Padrão | Descrição |
|---|---|---|---|
| `SICA_DB_PATH` | Não | `data/assinatura.db` | Arquivo do SQLite (dados e auditoria). |
| `SICA_STORAGE_DIR` | Não | `storage` | PDFs originais e finais (`storage/<id>/original.pdf`, `final.pdf`). |
| `SICA_SERVER_FILES_DIR` | Não | `server-files` | Onde o PDF final é **publicado** para o LIST/DOWNLOAD do SiCA. `none` desativa a publicação. |
| `MAX_PDF_BYTES` | Não | `20971520` (20 MB) | Tamanho máximo do PDF enviado. |
| `TOKEN_VALIDADE_DIAS` | Não | `7` | Validade do link de assinatura. |

### Não são variáveis de ambiente

| Item | Valor | Onde mudar |
|---|---|---|
| Porta HTTP(S) do gateway, host e porta do SiCA TCP | `8080`, `localhost`, `5000` | Argumentos do `GatewayWeb`: `[portaHttp] [hostTcp] [portaTcp]` |
| Porta do servidor TCP | `5000` (no macOS a 5000 costuma estar ocupada; use `5050`) | Argumento do `Servidor` |
| Limite de requisições por IP nas rotas do signatário | 120/min geral e 10/min para escrita | Constantes em `AssinaturaApi` |
| Máximo de signatários por envelope | 20 | `SignatarioServico` |
| Tamanho máximo dos formulários (sem arquivo) | 16 KB | `AssinaturaApi` |

## 1.3 O que acontece quando…

| Situação | Resultado |
|---|---|
| Sem `ADMIN_KEY` | Módulo de assinatura desativado; `/api/*` abertas (modo legado). |
| `EMAIL_PROVIDER=resend` sem `RESEND_API_KEY` | Falha na subida. |
| `EMAIL_PROVIDER=console` | Nenhum e-mail é enviado; o link aparece no terminal. O painel mostra "convites enviados" porque o sistema processou o envio. |
| Resend recusa o destinatário (403) | O envio fica registrado na auditoria como `email.failed`; o envelope não é desfeito. |
| Sem `SIGNING_CERT_PATH` | Finalização desativada (aviso no log). |
| TSA fora do ar | A assinatura é preservada; o envelope fica em `SENT`; repetir com `POST /envelopes/{id}/finalize` (503 enquanto falhar). |
| Sem `HTTPS_KEYSTORE_PATH` | HTTP sem criptografia, com aviso no log. |
| `HTTPS_KEYSTORE_PATH` definido sem senha correta | Falha na subida. |
| Número inválido (ex.: `MAX_PDF_BYTES=abc`) | Falha na subida. |

## 1.4 Perfis sugeridos

**Desenvolvimento local** (sem e-mail real, sem internet):
```bash
export ADMIN_KEY="<chave-de-teste>"
export EMAIL_PROVIDER=console
export TSA_URL=none                       # opcional, só offline
export SIGNING_CERT_PATH="certs/servico.p12"
export SIGNING_CERT_PASSWORD="<senha>"
export HTTPS_KEYSTORE_PATH="certs/https.p12"
export HTTPS_KEYSTORE_PASSWORD="<senha>"
export BASE_URL="https://localhost:8080"
```

**Demonstração** (e-mail real só para a sua conta Resend):
```bash
export EMAIL_PROVIDER=resend
export RESEND_API_KEY="re_..."            # destinatário = e-mail da conta
# demais variáveis como no perfil de desenvolvimento, com TSA_URL padrão
```

**Produção** (não é o estado atual do projeto):
- `BASE_URL` com o domínio público em `https://`; TLS válido (no próprio gateway ou no proxy).
- Certificado **A1 real** em `SIGNING_CERT_*`; `TSA_URL` de uma TSA confiável.
- Domínio verificado no Resend e `EMAIL_FROM` desse domínio.
- `SICA_DB_PATH`, `SICA_STORAGE_DIR` e `SICA_SERVER_FILES_DIR` em **disco persistente**, com backup.
- O servidor TCP do SiCA **fora da internet** (sem autenticação nem TLS); só rede interna.
- `ADMIN_KEY` longa e aleatória (por exemplo `openssl rand -hex 24`), trocada se vazar.

## 1.5 Pastas geradas em execução

| Pasta/arquivo | Conteúdo | No git? |
|---|---|---|
| `.env` | Segredos locais | Não (`.gitignore`) |
| `certs/` | AC de teste, `servico.p12`, `https.p12` | Não |
| `data/` | Banco SQLite (`.db`, `-wal`, `-shm`) | Não |
| `storage/` | PDFs originais e finais por envelope | Não |
| `server-files/` | Arquivos do SiCA e **PDFs finais assinados** publicados | Não neste clone (excluído em `.git/info/exclude`, não no `.gitignore` do repositório) |
| `logs/` | Reservada | `.gitkeep` |

> Os PDFs finais são contratos assinados. Como `server-files/` não está no `.gitignore`, em outro clone eles poderiam ser commitados por engano; vale acrescentar `server-files/` ao `.gitignore`.

---

# Parte 2 — Padrão de arquitetura

## 2.1 Visão geral

Aplicação **monolítica em Java 17**, **sem frameworks**: servidor HTTP do JDK
(`com.sun.net.httpserver`), JDBC puro com SQLite, PDFBox e BouncyCastle. O frontend é HTML,
CSS e JavaScript puros servidos pelo próprio gateway. O núcleo TCP do SiCA (`sica.server`,
`sica.client`, `sica.common`) é independente e permanece intacto.

```
Navegador ──HTTPS──► GatewayWeb ──┬── PainelWeb            (arquivos estáticos do painel)
(emissor/signatário)              ├── /api/*  ──socket TCP──► Servidor SiCA (server-files/)
                                  └── sica.assinatura (módulo de assinatura)
                                         │
   web  ──►  servico  ──►  repositorio ──► SQLite (data/)
                │    ├────►  pdf        ──► PDFBox, BouncyCastle, storage/, TSA (RFC 3161)
                │    ├────►  email      ──► console | Resend (HTTPS)
                │    └────►  seguranca  ──► tokens, SHA-256
                └──── modelo (registros imutáveis)        config (variáveis de ambiente)
```

## 2.2 Camadas e regras de dependência

| Pacote (`sica.assinatura.*`) | Responsabilidade | Pode depender de |
|---|---|---|
| `web` | HTTP: rotas, leitura de entrada, JSON, cabeçalhos de segurança, limite de taxa, tradução de exceções em códigos HTTP | `servico`, `modelo`, `config`, `util` |
| `servico` | **Regras de negócio**, transações, orquestração, auditoria | `repositorio`, `pdf`, `email`, `seguranca`, `modelo` |
| `repositorio` | SQL e esquema (`Banco`); nenhuma regra de negócio | `modelo` |
| `pdf` | Montagem do PDF, selo PAdES, carimbo de tempo, verificação | `modelo`, `seguranca` |
| `email` | Envio de mensagens (interface + implementações) | — |
| `seguranca` | Geração de token, SHA-256, comparação em tempo constante | — |
| `modelo` | Dados do domínio (registros imutáveis e enums de status) | — |
| `config` | Leitura e validação das variáveis de ambiente | — |
| `util` | Utilidades pequenas (JSON) | — |

Regras: a dependência vai **sempre de cima para baixo** (`web → servico → repositorio`);
`web` nunca fala com o banco; `repositorio` nunca decide regra; `modelo`, `config` e
`seguranca` não conhecem camadas superiores. Fora do módulo, `sica.web` (`GatewayWeb`,
`PainelWeb`, `ServidorHttp`, `SicaTcpClient`) é a fachada HTTP do SiCA.

## 2.3 Padrões adotados

**Injeção por construtor e raiz de composição.** Serviços recebem suas dependências
(`EnvelopeServico(banco, storage, maxPdf)`); quem monta o grafo é `AssinaturaApi.criar(...)`.
Há variantes que aceitam um `EmailSender` e limites de taxa, usadas pelos testes.

**Strategy atrás de interfaces.** `EmailSender` (`ConsoleEmailSender`, `ResendEmailSender`) e
`CarimboTempo` (`ClienteTsaHttp`) são pontos de troca. A fábrica `EmailSenders.criar(config)`
escolhe a implementação pela configuração. O resto do código não sabe qual está ativa.

**Repositório sem estado, transação no serviço.** Os repositórios são classes de métodos
estáticos que recebem a `Connection`; **quem abre e confirma a transação é o serviço**, o que
permite gravar dados e evento de auditoria **atomicamente**.

**Concorrência por compare-and-set no banco.** Transições usam
`UPDATE ... WHERE status = ?` (assinatura única, mudança de status); `reservar` adquire o
bloqueio de escrita do SQLite para serializar operações; conexões abrem com
`foreign_keys=ON`, `busy_timeout` e `journal_mode=WAL`. A finalização é **idempotente** e
serializada por envelope (mapa de travas), para o caso de dois últimos signatários
assinarem ao mesmo tempo.

**Efeitos externos depois do commit.** E-mails, publicação em `server-files/` e carimbo de
tempo acontecem após a gravação. Falha de e-mail ou de publicação **não desfaz** a
assinatura; fica registrada na auditoria e pode ser repetida.

**Auditoria imutável imposta pelo banco.** `audit_events` só aceita inserção: triggers
abortam `UPDATE` e `DELETE`. A imutabilidade não depende de disciplina do código.

**Exceções de domínio traduzidas na borda.** Os serviços lançam exceções tipadas; a camada
`web` as converte em respostas `{"erro":"..."}` sem vazar detalhes internos.

| Exceção / condição | HTTP |
|---|---|
| `ValidacaoException` | 400 |
| Chave ausente ou inválida | 401 |
| `NaoEncontradoException` (inclui link desconhecido) | 404 |
| Método incorreto | 405 |
| `ConflitoException` (ex.: já assinou, estado inválido) | 409 |
| `LinkExpiradoException` | 410 |
| Corpo acima do limite | 413 |
| Limite de requisições por IP | 429 |
| `FinalizacaoException` (ex.: TSA indisponível) | 503 |
| Erro inesperado | 500 (detalhe só no log) |

**Modelo imutável.** `Envelope`, `Signatario`, `CampoAssinatura`, `EventoAuditoria` e
`AssinaturaRegistrada` são registros (records) de Java; o estado muda por `UPDATE`
condicional no banco, nunca por mutação em memória.

**Segurança por padrão.**
- Tokens de 32 bytes (`SecureRandom`); no banco só o **hash**.
- Nome de arquivo do usuário **nunca** entra em caminho de disco (os caminhos usam UUID).
- Validação antes de processar: PDF por assinatura de bytes e carregamento completo; PNG com
  limite de dimensões antes de decodificar; tamanho máximo de requisição.
- Original nunca sobrescrito (`CREATE_NEW`); o PDF final é um arquivo novo com hash próprio.
- Cabeçalhos: CSP restritiva, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
  `Cache-Control: no-store`.

**Frontend estático sobre API JSON.** As páginas não têm renderização no servidor: o
JavaScript chama a API e monta o DOM com `textContent` (nunca `innerHTML`), o que, somado à
CSP sem inline, elimina a principal via de XSS. A chave do emissor fica só em
`sessionStorage`.

## 2.4 Máquinas de estado

```
Envelope:    DRAFT ──► SENT ──► COMPLETED
                         ├────► DECLINED   (um signatário recusou)
                         └────► EXPIRED / CANCELED   (previstos, ainda não implementados)

Signatário:  PENDING ──► VIEWED ──► SIGNED
                            └─────► DECLINED
```

Os valores válidos também são impostos por `CHECK` no esquema SQLite.

## 2.5 Fluxo de uma requisição (exemplo: assinar)

1. `POST /sign/{token}` chega ao `SignatarioApi`, que aplica o limite de taxa por IP.
2. A API lê o multipart (nome, hash, aceite, PNG) respeitando limites de tamanho.
3. `AssinaturaServico.assinar` busca o signatário pelo **hash do token**, valida validade,
   estado, nome (sem acento/caixa), hash do documento, aceite e imagem.
4. Em **uma transação**: marca `SIGNED` por `UPDATE ... WHERE status <> 'SIGNED'` (zero linhas
   = rejeita), grava a assinatura e o evento `signer.signed`.
5. Se foi o último, `FinalizacaoServico` monta o PDF, aplica o selo e o carimbo, grava
   `final.pdf`, marca `COMPLETED`, publica no SiCA e envia os e-mails (depois do commit).
6. A resposta traz `todosAssinaram`; erros viram `{"erro":...}` com o código da tabela acima.

## 2.6 Convenções de código

- **Idioma:** nomes de classes, métodos e mensagens em português; comentários explicam o
  porquê, não o quê.
- **Java 17**, dependências com **versão exata** no `pom.xml` (sem intervalos) e licenças
  permissivas (evitou-se iText/OpenPDF).
- **Testes por camada** (JUnit): serviços com SQLite e diretório temporários; API com servidor
  HTTP em porta efêmera; PDFs, certificados e TSA **de teste** (`PdfsDeTeste`,
  `CertificadosDeTeste`, `TsaFalsa`, `EmailFalso`). Concorrência é testada com várias threads.
- **Sem segredos em testes ou logs**; teste específico garante que o token em claro não vai
  para banco nem auditoria.

## 2.7 Como estender

**Nova variável de ambiente:** (1) campo e leitura em `Configuracao`, com validação e
padrão; (2) teste em `ConfiguracaoTest`; (3) linha no `.env.example`; (4) tabela da seção 1.2
deste documento e do README.

**Novo provedor de e-mail:** implemente `EmailSender`, aceite o nome em
`Configuracao.provedorEmail` e registre-o em `EmailSenders.criar`. Nada mais muda.

**Nova rota:** regra em um `*Servico`; SQL em um `*Repositorio`; rota e tradução de exceções
na `web`; teste de serviço e teste de API (incluindo 401/404/405); atualize a tabela da API
do README.

**Novo status ou evento de auditoria:** ajuste o `CHECK` do esquema e o enum correspondente;
acrescente o rótulo no painel (`painel.js`, tabelas `STATUS_*` e `EVENTOS`).

## 2.8 Limites conhecidos

- **Nó único:** SQLite e disco local; não há escala horizontal nem alta disponibilidade.
- **Sem exclusão/retenção automática** de dados; a auditoria é imutável por desenho.
- **TCP do SiCA sem autenticação nem TLS:** manter em rede interna.
- **Identificação do signatário** pelo acesso ao e-mail (sem OTP).
- **Certificado de teste:** o selo aparece como "não confiável" em leitores de PDF.
- **Expiração e cancelamento de envelopes** (`EXPIRED`/`CANCELED`) ainda não implementados.
- **Sem verificação pública** (`/verify`): o `VerificadorPdf` existe, falta expor a rota.
