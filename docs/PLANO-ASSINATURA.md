# Plano de implementação — Módulo de Assinatura Eletrônica no SiCA

> Evolui o SiCA (transferência de arquivos via TCP) para também permitir a
> **assinatura eletrônica de documentos PDF** por link único, com hash,
> data/hora, trilha de auditoria, selo digital PAdES e verificação posterior.
>
> Estas são as premissas. Mudanças devem ser propostas explicitamente.

---

## 1. Decisões já tomadas

| Tema | Decisão |
|---|---|
| Linguagem / build | Java 17 + Maven (mesmo projeto do SiCA) |
| Restrição | Somente ferramentas gratuitas ou com plano gratuito |
| Tipo de assinatura | Assinatura **eletrônica simples/avançada** (MP 2.200-2/2001, Lei 14.063/2020). ICP-Brasil do signatário está **fora do escopo** |
| Certificado | De **teste (autoassinado)**; trocar por A1 real = apenas configuração |
| Identificação do signatário | **Link único** enviado por e-mail (sem OTP) + nome completo digitado |
| Assinatura única | Cada link assina **uma única vez** (garantido de forma atômica no banco) |
| E-mail | Resend, atrás de uma interface `EmailSender` (com modo "console" para testes) |
| Banco | SQLite (arquivo local) |
| Frontend | Página HTML simples servida pelo `GatewayWeb`, acessada por deep link |
| Servidor HTTP | `com.sun.net.httpserver` (já usado pelo `GatewayWeb`) |
| Núcleo TCP do SiCA | Permanece intacto |

### Fora do escopo (adiar)

Webhooks, API Keys, painel com login/JWT, React, Supabase, MinIO, Docker,
ordem de assinatura, múltiplas versões, OTP, assinatura qualificada
ICP-Brasil, biometria, SMS/WhatsApp.

---

## 2. O que a assinatura prova (evidências)

1. **Hash SHA-256** do documento, conferido no momento da assinatura (o
   signatário assina exatamente o que viu).
2. **Link único e secreto** por signatário (prova acesso ao e-mail).
3. **Trilha de auditoria** (data/hora, IP, user agent, hash, nome digitado),
   somente inserção.
4. **Selo digital PAdES** aplicado pelo serviço, que torna qualquer alteração
   posterior detectável.
5. **Carimbo de tempo RFC 3161** (FreeTSA), que prova quando o documento foi
   finalizado.

> Limitação a documentar: a identificação se baseia no acesso ao e-mail. Quem
> receber o link encaminhado consegue assinar.

---

## 3. Arquitetura

```
Navegador (página HTML, deep link)
        │ HTTP
        ▼
GatewayWeb (rotas /envelopes, /sign, /verify)
        │
        ├── sica.assinatura  (regras: envelope, signatário, auditoria, finalização)
        │        ├── SQLite          (dados e auditoria)
        │        ├── PDFBox          (imagem, página de auditoria, PDF)
        │        ├── BouncyCastle    (CMS/PAdES, carimbo RFC 3161)
        │        └── EmailSender     (Resend | console)
        │
        └── server-files/final.pdf  → visível ao LIST/DOWNLOAD do SiCA (TCP)
```

### Estrutura de pacotes

```
sica/
├── assinatura/
│   ├── modelo/        Envelope, Signatario, EventoAuditoria, StatusEnvelope...
│   ├── repositorio/   acesso ao SQLite (JDBC puro)
│   ├── servico/       EnvelopeServico, AssinaturaServico, VerificacaoServico
│   ├── pdf/           ManipuladorPdf, PaginaAuditoria, SeloPades, ClienteCarimboTempo
│   ├── email/         EmailSender, ResendEmailSender, ConsoleEmailSender
│   ├── seguranca/     TokenServico, Hashes
│   └── config/        Configuracao (variáveis de ambiente)
└── web/               GatewayWeb (+ rotas novas), recursos estáticos em resources/web/
```

---

## 4. Dependências (a fixar com versão exata no `pom.xml`)

| Dependência | Uso |
|---|---|
| Apache PDFBox 3.x | Manipular PDF, inserir imagem, página de auditoria |
| BouncyCastle (`bcprov`, `bcpkix`) | Assinatura CMS, carimbo de tempo, validação |
| `sqlite-jdbc` | Banco local |
| JUnit 5 | Testes |

Antes de adicionar, confirmar a versão estável atual de cada uma no Maven
Central e fixá-la (sem intervalos). Evitar iText/OpenPDF (licenças AGPL/LGPL).

---

## 5. Modelo de dados (SQLite)

- **envelopes**: id, titulo, status, sha256_original, sha256_final,
  caminho_original, caminho_final, expira_em, criado_em, concluido_em.
- **signers**: id, envelope_id, nome, email, status, token_hash,
  token_expira_em, nome_confirmado, assinatura_png (BLOB), assinado_em.
- **signature_fields**: id, signer_id, pagina, x, y, largura, altura.
- **audit_events**: id, envelope_id, signer_id (opcional), tipo, ip,
  user_agent, sha256_documento, detalhe, ocorrido_em.
  **Somente inserção**: trigger bloqueando `UPDATE` e `DELETE`.

Status do envelope: `DRAFT → SENT → COMPLETED` (e `EXPIRED`, `CANCELED`).
Status do signatário: `PENDING → VIEWED → SIGNED` (ou `DECLINED`).

---

## 6. Contrato da API (MVP)

| Método | Rota | Descrição |
|---|---|---|
| POST | `/envelopes` | Cria envelope (PDF + título); calcula SHA-256 |
| POST | `/envelopes/{id}/signers` | Adiciona signatários e posição da assinatura |
| POST | `/envelopes/{id}/send` | Gera tokens e envia e-mails com o link |
| GET | `/envelopes/{id}` | Status do envelope e signatários |
| GET | `/envelopes/{id}/audit` | Trilha de auditoria |
| GET | `/envelopes/{id}/download` | Baixa o PDF final |
| GET | `/sign/{token}` | Dados do envelope e PDF (registra visualização) |
| POST | `/sign/{token}` | Envia assinatura (PNG, nome, hash visto) |
| POST | `/sign/{token}/decline` | Recusa com motivo |
| POST | `/verify` | Verifica autenticidade de um PDF |

Rotas do emissor protegidas por uma **chave de administrador** em variável de
ambiente (`ADMIN_KEY`, cabeçalho `X-Admin-Key`).

---

## 7. Regras de segurança

- Token do link: 32 bytes aleatórios (`SecureRandom`), guardado **apenas como
  hash** SHA-256, com expiração.
- Assinatura única via `UPDATE ... SET status='SIGNED' WHERE id=? AND status<>'SIGNED'`;
  zero linhas afetadas = rejeitar.
- Finalização em transação, idempotente, contando signatários pendentes sem
  condição de corrida (dois últimos assinando ao mesmo tempo).
- Conferir `sha256` enviado pelo cliente == `sha256` do documento atual.
- Aceitar somente PDF (validar assinatura `%PDF-`), com limite de tamanho
  configurável.
- Nome de arquivo sanitizado (reaproveitar a lógica de `resolverSeguro`).
- PDF original nunca é sobrescrito; o final é um arquivo novo.
- Chave privada do certificado **fora do repositório** (`SIGNING_CERT_PATH`,
  `SIGNING_CERT_PASSWORD`); adicionar ao `.gitignore`.
- IP do signatário: só confiar em `X-Forwarded-For` se houver proxy confiável
  configurado.
- Rate limit simples nas rotas `/sign`.
- Imagem de assinatura: validar PNG e limitar dimensões e tamanho.

---

## 8. Etapas de implementação

Cada etapa termina com **build verde** (`mvn clean package`) e testes passando.

### Etapa 0 — Fundação
- [x] Adicionar dependências ao `pom.xml` (versões exatas) e JUnit 5 + Surefire.
      (PDFBox 3.0.8, BouncyCastle 1.87, sqlite-jdbc 3.54.1.0, JUnit 6.1.3, Surefire 3.6.1)
- [x] Classe `Configuracao` lendo variáveis de ambiente (com padrões).
- [x] Inicialização do SQLite e criação do esquema (seção 5) + trigger de auditoria (`Banco`).
- [x] Script `scripts/gerar-certificado-teste.sh` (AC de teste + certificado `.p12`).
- [x] `.gitignore` para `*.p12`, `*.pfx`, `*.db`, `storage/`, `certs/`.
- **Pronto quando:** o banco é criado e o teste de esquema passa. ✔ (10 testes, build verde)

### Etapa 1 — Envelopes e hash
- [x] `POST /envelopes` (multipart): valida PDF, calcula SHA-256, grava original.
- [x] `GET /envelopes/{id}`.
- [x] Evento de auditoria `envelope.created` (na mesma transação do envelope).
- **Pronto quando:** teste envia um PDF e o hash retornado confere com o do arquivo. ✔
  (39 testes; verificado também com o gateway real e `curl`)

> Notas da Etapa 1: as rotas só são registradas no `GatewayWeb` quando `ADMIN_KEY`
> está definida (sem ela, o SiCA funciona como antes). PDFs protegidos por senha,
> corrompidos ou acima de `MAX_PDF_BYTES` são recusados. O nome do arquivo enviado
> nunca participa do caminho em disco (`storage/<uuid>/original.pdf`).

### Etapa 2 — Signatários e envio
- [x] `POST /envelopes/{id}/signers` (nome, e-mail, posição da assinatura).
- [x] Interface `EmailSender` + `ConsoleEmailSender` + `ResendEmailSender`.
- [x] `POST /envelopes/{id}/send`: gera token, grava hash, envia e-mail, status `SENT`.
- [x] Eventos `signer.added`, `envelope.sent` / `envelope.resent`, `email.sent` / `email.failed`.
- **Pronto quando:** o link aparece no console e o token em claro não está no banco. ✔
  (70 testes; verificado também com o gateway real e `curl`)

> Notas da Etapa 2:
> - O Resend é chamado direto pela API HTTP (`POST https://api.resend.com/emails`);
>   **não é necessário MCP** em runtime. Sem domínio verificado, o Resend só
>   entrega ao e-mail da própria conta (HTTP 403 para outros) — por isso o padrão
>   é `EMAIL_PROVIDER=console`.
> - Variáveis novas: `BASE_URL`, `EMAIL_PROVIDER` (`console`|`resend`),
>   `RESEND_API_KEY`, `EMAIL_FROM`.
> - Os e-mails saem **depois** do commit; falha de entrega não desfaz o envio e
>   fica na auditoria. Chamar `/send` de novo reenvia aos pendentes com tokens
>   novos (os links anteriores deixam de valer).
> - Formulário de signatário aceita `application/x-www-form-urlencoded` ou multipart.
> - Coordenadas em pontos do PDF, origem no canto inferior esquerdo; validadas
>   contra a página do documento. Até 20 signatários por envelope; e-mail único
>   por envelope (sem diferenciar maiúsculas).
> - O link aponta para `BASE_URL/assinar/{token}` (página criada na Etapa 3).

### Etapa 3 — Tela do signatário
- [x] `GET /sign/{token}`: valida token e expiração, registra `signer.viewed`.
- [x] Página HTML: exibir PDF, campo de nome, canvas de assinatura, termo de aceite.
- [x] `POST /sign/{token}`: confere hash, valida PNG e nome, `UPDATE` atômico, registra `signer.signed`.
- [x] `POST /sign/{token}/decline`.
- **Pronto quando:** testes cobrem hash errado, token expirado, segunda assinatura (rejeitada) e assinaturas simultâneas. ✔
  (102 testes; página verificada no Chrome headless e assinatura via `curl`)

> Notas da Etapa 3:
> - Rotas: `GET /assinar/{token}` (página), `GET /sign/{token}`, `GET /sign/{token}/documento`,
>   `GET /sign/{token}/paginas/{n}` (PNG renderizado pelo PDFBox), `POST /sign/{token}`
>   (multipart: `nome`, `sha256`, `aceite=true`, arquivo `assinatura`), `POST /sign/{token}/decline`.
> - Sem React/react-pdf/signature_pad: as páginas são imagens renderizadas no servidor
>   (funciona em qualquer celular) e o desenho é um `<canvas>` com pointer events.
> - O navegador baixa o PDF e calcula o SHA-256 com `crypto.subtle`; esse é o hash
>   enviado. Exige contexto seguro (HTTPS ou `localhost`).
> - O nome digitado precisa conferir com o do convite (ignorando acentos, caixa e espaços).
> - Recusa leva o envelope a `DECLINED` (novo status) e impede os demais.
> - Link desconhecido/substituído/cancelado → 404; vencido → 410; já usado → 409.
>   Depois de assinado, o link continua só mostrando o status.
> - Segurança: CSP restritiva, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`,
>   `no-store`; rate limit por IP (120 req/min geral, 10 POST/min); PNG validado por
>   dimensões antes de decodificar e re-codificado; token nunca vai para log.
> - `todosAssinaram=true` é devolvido ao último signatário; a finalização (PDF
>   selado, `COMPLETED`) entra na Etapa 4.

### Etapa 4 — Finalização com selo
- [x] Inserir imagens de assinatura nas posições (PDFBox).
- [x] Gerar e anexar a página de auditoria (dados, signatários, eventos, hash original, link de verificação).
- [x] Aplicar selo PAdES (BouncyCastle) com o certificado configurado.
- [x] Obter carimbo RFC 3161 do FreeTSA (URL configurável; política em caso de falha definida).
- [x] Gravar `final.pdf` com seu hash; status `COMPLETED`; evento `envelope.completed`; cópia em `server-files/`.
- [x] `GET /envelopes/{id}/download`, `GET /envelopes/{id}/audit`, `POST /envelopes/{id}/finalize`, `GET /sign/{token}/final`.
- **Pronto quando:** o PDF final é validado e o hash final está registrado. ✔
  (129 testes; fluxo real com certificado do script + FreeTSA; validado também com `pdfsig` do poppler:
  "Signature is Valid", "Total document signed")

> Notas da Etapa 4:
> - Selo `ETSI.CAdES.detached` (PAdES B-T): `signing-certificate-v2`, sem `signing-time`
>   (fica em `/M`), carimbo RFC 3161 como `signature-time-stamp`. Atualização incremental:
>   o PDF montado fica intacto antes do selo.
> - A finalização ocorre automaticamente após a última assinatura. **Política de falha da
>   TSA:** a assinatura nunca é perdida; o envelope fica em `SENT`, registra
>   `envelope.finalize_failed` e o emissor repete com `POST /envelopes/{id}/finalize` (503
>   enquanto falhar). Idempotente e serializada por envelope.
> - Sem `SIGNING_CERT_PATH` o módulo sobe, mas a finalização fica desativada (aviso no log).
>   `TSA_URL=none` sela sem carimbo (só desenvolvimento).
> - O PDF final é enviado por e-mail (anexo) a todos e publicado em `SICA_SERVER_FILES_DIR`
>   (padrão `server-files/`), aparecendo no `LIST`/`DOWNLOAD` do SiCA.
> - `VerificadorPdf` (criptografia, cobertura do `/ByteRange`, carimbo) já existe e será
>   exposto pelo `/verify` na Etapa 5.
> - Com certificado autoassinado, leitores como o Adobe mostram o selo como "não confiável"
>   até importar `certs/ac-teste.crt` como confiável.

### Etapa 5 — Verificação
- [ ] `POST /verify`: calcula hash, procura no banco, valida o selo e o carimbo.
- [ ] Resposta: autêntico/íntegro ou o motivo da falha.
- **Pronto quando:** teste altera 1 byte do PDF final e a verificação falha; o original passa.

### Etapa 6 — Acabamento
- [ ] Expiração de envelopes e `POST /envelopes/{id}/cancel`.
- [ ] Documentar no `README.md` (uso, configuração, limitações jurídicas).
- [ ] Atualizar o diagrama de arquitetura.

---

## 9. Plano de testes

- **Unitários:** hashes, token (hash e expiração), conferência do hash visto.
- **Integração:** fluxo completo com `ConsoleEmailSender` e SQLite temporário.
- **Concorrência:** duas assinaturas simultâneas do mesmo token (apenas uma vence)
  e dois últimos signatários finalizando juntos (finaliza uma única vez).
- **Segurança:** upload não-PDF, PNG inválido, path traversal, token inexistente.
- **Verificação:** PDF íntegro passa; PDF alterado falha.

---

## 10. Riscos e pontos de atenção

| Risco | Mitigação |
|---|---|
| Resend gratuito pode exigir domínio verificado | Confirmar na documentação oficial; usar `ConsoleEmailSender` em desenvolvimento |
| FreeTSA sem garantia de disponibilidade | URL configurável; definir se a falha bloqueia ou reenfileira a finalização |
| Validação do selo no `/verify` é manual com BouncyCastle | Reservar tempo na Etapa 5; cobrir com testes de adulteração |
| Certificado de teste aparece como "não confiável" | Importar a AC de teste no leitor em demonstrações; documentar |
| Link encaminhado por terceiros | Nome digitado + auditoria + expiração; documentar a limitação |
| Limites dos planos gratuitos mudam | Confirmar na documentação oficial antes de depender |

---

## 11. Definição de pronto (MVP)

- [ ] Emissor cria envelope, adiciona signatários e envia o link.
- [ ] Signatário abre o deep link, vê o PDF e assina **uma única vez**.
- [ ] Hash, data/hora, IP e nome ficam na auditoria (imutável).
- [ ] PDF final com página de auditoria, selo PAdES e carimbo de tempo.
- [ ] `final.pdf` aparece no `LIST` e é baixável via `DOWNLOAD` do SiCA.
- [ ] `/verify` aprova o original e reprova o adulterado.
- [ ] `mvn clean package` verde, testes passando, README atualizado.
