# SiCA — Sistema de Compartilhamento de Arquivos

> **Disciplina:** Desenvolvimento de Software Cliente/Servidor
> **Professor:** Thalles Santos
> **Curso:** Análise e Desenvolvimento de Sistemas — PUC-GO
> **Atividade:** Fórum Avaliativo

---

## Enunciado

> É muito comum empresas utilizarem um sistema de compartilhamento de arquivos
> entre as máquinas da organização (geralmente, o sistema é conhecido pelo
> acrônimo **"SiCA" — Sistema de Compartilhamento de Arquivos**). Nesse contexto,
> considere o problema de realizar a transferência de um arquivo qualquer via
> rede de computadores. Desenvolva uma aplicação na linguagem de programação
> utilizada na disciplina para realizar essa tarefa, entre um **cliente** e um
> **servidor**. Utilize **sockets TCP** na sua construção.
>
> O cliente deverá poder, após conectar-se ao servidor, fazer o **envio** de um
> arquivo, **listar** todos os arquivos presentes no servidor e, por fim,
> **baixar** algum dos arquivos que estejam disponíveis. O servidor, por sua
> vez, deve receber e processar requisições de maneira a realizar tais
> atividades.
>
> Documente o seu código na forma de comentários detalhados junto aos principais
> métodos implementados. Descreva de maneira suficiente o funcionamento da sua
> aplicação.

### Como este projeto atende ao enunciado

| Requisito do enunciado                          | Onde é atendido                                            |
|-------------------------------------------------|-----------------------------------------------------------|
| Linguagem da disciplina (**Java**)              | Todo o projeto, apenas biblioteca padrão                  |
| **Sockets TCP** entre cliente e servidor        | `ServerSocket`/`Socket` (`Servidor`, `ClienteHandler`, `Cliente`) |
| Cliente **envia** arquivo (upload)              | Comando `UPLOAD` — menu opção 1 / interface web           |
| Cliente **lista** arquivos                      | Comando `LIST` — menu opção 2 / interface web             |
| Cliente **baixa** arquivo (download)            | Comando `DOWNLOAD` — menu opção 3 / interface web         |
| Servidor **processa requisições**               | Thread por conexão (`ClienteHandler`)                     |
| **Comentários detalhados** nos métodos          | Javadoc em todas as classes principais                    |
| **Descrição** do funcionamento                  | Este README                                               |

---

Aplicação cliente-servidor em **Java** para transferência de arquivos via rede,
usando **sockets TCP**. O cliente pode, após conectar-se ao servidor:

- **enviar** um arquivo (upload);
- **listar** todos os arquivos disponíveis no servidor;
- **baixar** um arquivo (download).

O projeto usa apenas a biblioteca padrão do Java — **sem dependências externas**.

Além do cliente de linha de comando, há uma **interface web simples** (gateway
leve) que traduz ações do navegador em requisições ao servidor TCP.

---

## Arquitetura

```
                 (A) núcleo exigido — sockets TCP
   ┌────────────────┐        socket TCP        ┌───────────────────────┐
   │  Cliente CLI   │ ───────────────────────► │   Servidor SiCA (TCP) │
   │  (Cliente.java)│                          │  thread por conexão   │
   └────────────────┘                          │  LIST/UPLOAD/DOWNLOAD │
                                               └───────────┬───────────┘
   ┌────────────────┐   HTTP    ┌───────────┐  socket TCP  │  filesystem
   │  Navegador     │ ────────► │ Gateway   │ ────────────►│  (server-files/)
   │  (interface web)│          │ Web (HTTP)│              ▼
   └────────────────┘           └───────────┘         arquivos
```

O **núcleo** da aplicação é o par Cliente/Servidor sobre **sockets TCP**. O
gateway web é apenas uma fachada opcional: ele próprio age como um cliente TCP
do servidor SiCA.

### Estrutura de diretórios

```
cliente-server/
├── pom.xml                            # build e dependências (Maven)
├── src/
│   └── main/
│       └── java/
│           └── sica/
│               ├── common/Protocolo.java      # comandos e helpers do protocolo
│               ├── server/Servidor.java       # aceita conexões (thread por cliente)
│               ├── server/ClienteHandler.java # processa requisições de um cliente
│               ├── client/Cliente.java        # cliente CLI interativo
│               └── web/
│                   ├── SicaTcpClient.java     # cliente TCP usado pelo gateway
│                   └── GatewayWeb.java         # servidor HTTP + interface web
├── target/             # gerado pelo Maven — classes e JAR
├── server-files/       # criado em runtime — arquivos no servidor
├── client-downloads/   # criado em runtime — downloads do cliente CLI
├── logs/               # logs de execução (conteúdo não versionado)
└── README.md
```

---

## Protocolo de comunicação

Como o TCP entrega um fluxo contínuo de bytes (sem noção de "mensagem"),
definimos um protocolo de aplicação próprio, todo sobre
`DataInputStream`/`DataOutputStream`.

| Comando    | Cliente envia                                   | Servidor responde                                  |
|------------|-------------------------------------------------|----------------------------------------------------|
| `LIST`     | `"LIST"`                                         | `int qtd` + `qtd` × (`String nome`, `long tamanho`)|
| `UPLOAD`   | `"UPLOAD"`, `String nome`, `long tamanho`, bytes | `String status`, `String mensagem`                 |
| `DOWNLOAD` | `"DOWNLOAD"`, `String nome`                      | `String status`; se `OK`: `long tamanho` + bytes   |
| `SAIR`     | `"SAIR"`                                         | (encerra a conexão)                                |

- **Strings** são enviadas com `writeUTF` (prefixadas pelo tamanho).
- **Arquivos** são transferidos em **blocos de 8 KB** (`Protocolo.transferir`),
  precedidos pelo tamanho total (`long`), evitando carregar tudo em memória.

### Segurança

O servidor **sanitiza** o nome recebido (`resolverSeguro`): descarta componentes
de caminho e garante que o arquivo resolvido permaneça dentro de `server-files/`,
prevenindo *path traversal* (ex.: `../../etc/passwd`).

### Concorrência

O servidor usa o modelo **thread por conexão**: cada cliente aceito é atendido em
uma `Thread` dedicada (`ClienteHandler`), permitindo múltiplos clientes
simultâneos.

---

## Pré-requisitos (todos os sistemas)

É necessário ter o **JDK 17 ou superior** e o **Maven** instalados. O projeto usa
apenas a biblioteca padrão do Java (sem dependências externas); o Maven cuida do
*build* e fica pronto como ponto de extensão para dependências futuras. Verifique
se `java` e `mvn` estão disponíveis no terminal:

```bash
java -version
mvn -version
```

Se algum comando não for reconhecido, instale-os:

- **macOS:** `brew install openjdk@17 maven` (ou baixe o JDK da Adoptium/Temurin).
- **Windows:** baixe o JDK em <https://adoptium.net> (marque "Add to PATH") e o
  Maven em <https://maven.apache.org/download.cgi> (adicione a pasta `bin` ao PATH).
- **Linux (Debian/Ubuntu):** `sudo apt install openjdk-17-jdk maven`
  (Fedora: `sudo dnf install java-17-openjdk-devel maven`).

> Todos os comandos abaixo devem ser executados **a partir da raiz do projeto**
> (a pasta que contém o `pom.xml` e este `README.md`).

---

## Passo a passo por sistema operacional

O funcionamento é o mesmo em todos os sistemas — muda apenas a **sintaxe do
terminal** em pequenos detalhes. Em todos os casos você vai:

1. **compilar** o projeto uma vez com o Maven (gera a pasta `target/`);
2. abrir **terminais separados** para o servidor e para o cliente/interface web.

> **Porta padrão:** o servidor escuta em `localhost:5000` por padrão. **No macOS**,
> a porta 5000 costuma estar ocupada pelo *AirPlay Receiver* (ControlCenter); nesse
> caso, suba em outra porta, por exemplo `5050`, passando-a como argumento (veja
> abaixo). A interface web abre em `http://localhost:8080`.

### 🍎 macOS (Terminal / zsh) · 🐧 Linux (bash) · 🪟 Windows (PowerShell)

Os comandos do Maven são iguais nos três sistemas.

**1) Compilar / gerar o JAR** (uma vez, ou sempre que alterar o código):

```bash
mvn clean package
```

Isso compila os fontes e gera `target/sica-1.0.0.jar`.

**2) Iniciar o servidor** — deixe este terminal aberto:

```bash
mvn exec:java -Dexec.mainClass=sica.server.Servidor
# escolher outra porta (recomendado no macOS): 
mvn exec:java -Dexec.mainClass=sica.server.Servidor -Dexec.args="5050"
```

**3a) Iniciar o cliente de linha de comando** — em um **segundo** terminal:

```bash
mvn exec:java -Dexec.mainClass=sica.client.Cliente
# apontando host/porta: 
mvn exec:java -Dexec.mainClass=sica.client.Cliente -Dexec.args="localhost 5050"
```

**3b) OU iniciar a interface web** — em um segundo terminal:

```bash
mvn exec:java -Dexec.mainClass=sica.web.GatewayWeb
# escolher porta web / host / porta TCP do servidor: 
mvn exec:java -Dexec.mainClass=sica.web.GatewayWeb -Dexec.args="8080 localhost 5050"
```

Depois abra <http://localhost:8080> no navegador.

> **Alternativa sem Maven em runtime:** após `mvn clean package`, você também pode
> executar direto pelas classes compiladas:
> `java -cp target/classes sica.server.Servidor 5050`
> (troque a classe por `sica.client.Cliente` ou `sica.web.GatewayWeb` conforme o caso).
> No Windows, o separador de classpath entre múltiplos diretórios é `;` (não `:`).
> Se o Firewall do Windows perguntar, autorize o Java a comunicar na rede local.

---

## Usando a aplicação

**Cliente de linha de comando** — apresenta o menu:

```
1 - Enviar arquivo (upload)
2 - Listar arquivos do servidor
3 - Baixar arquivo (download)
4 - Sair
```

- Opção **1**: informe o caminho de um arquivo local para enviar ao servidor.
- Opção **2**: lista os arquivos disponíveis no servidor (nome e tamanho).
- Opção **3**: informe o nome de um arquivo para baixá-lo.
- Downloads do cliente CLI são salvos em `client-downloads/`.

**Interface web** — em <http://localhost:8080> você pode escolher um arquivo e
enviar, ver a lista de arquivos no servidor e clicar em "baixar" em cada um.

Os arquivos recebidos pelo servidor ficam em `server-files/`. Ambas as pastas
(`server-files/` e `client-downloads/`) são criadas automaticamente na primeira
execução.

---

## Teste rápido do fluxo (verificação)

macOS/Linux:

```bash
# 1. Compilar
mvn clean package

# 2. Subir o servidor (em um terminal) — porta 5050 para evitar conflito no macOS
mvn exec:java -Dexec.mainClass=sica.server.Servidor -Dexec.args="5050"

# 3. No cliente (outro terminal): opção 1 (enviar um arquivo qualquer),
#    opção 2 (listar) e opção 3 (baixar). Aponte para a mesma porta:
#    mvn exec:java -Dexec.mainClass=sica.client.Cliente -Dexec.args="localhost 5050"
# 4. Conferir que o arquivo em client-downloads/ é idêntico ao original.
```

Windows (PowerShell), após enviar e baixar um arquivo, compare os dois:

```powershell
Compare-Object (Get-Content -Raw origem.txt) (Get-Content -Raw client-downloads\origem.txt)
# sem saída = arquivos idênticos
```

---

## Solução de problemas

- **`Address already in use` / `BindException` ao subir o servidor:** já existe
  um servidor rodando na mesma porta (ou uma execução anterior não foi
  encerrada). **No macOS**, a porta 5000 costuma estar ocupada pelo *AirPlay
  Receiver* (ControlCenter). Suba em outra porta, por exemplo:
  `mvn exec:java -Dexec.mainClass=sica.server.Servidor -Dexec.args="5050"`
  (e aponte o cliente para ela com `-Dexec.args="localhost 5050"`).
- **`Connection refused` no cliente:** o servidor não está no ar, ou o cliente
  está usando host/porta diferentes dos do servidor. Suba o servidor primeiro.
- **`java`/`mvn` não reconhecido:** o JDK ou o Maven não estão instalados ou não
  estão no PATH (veja os pré-requisitos acima).
- **Nada aparece no navegador:** confirme que **o servidor** (`Servidor`) **e** o
  **gateway** (`GatewayWeb`) estão ambos rodando, e que a porta web (8080) não
  está ocupada por outro programa.

## Melhoria futura — Arquitetura B (versão "produção")

A versão atual (Opção A) atende ao objetivo com o mínimo de peças. Uma evolução
natural, mais próxima de um sistema real, seria:

```
Navegador → (HTTP/REST) → API Gateway → (socket TCP) → Servidor SiCA
                                                          │        │
                                              filesystem  │        │ metadados
                                              (bytes dos  ▼        ▼
                                               arquivos)  disco   PostgreSQL
```

- **PostgreSQL para metadados** (não para os bytes): guardar nome, tamanho,
  dono, data de envio e *hash* de cada arquivo. O conteúdo continua no
  filesystem, que é o padrão recomendado (o banco fica leve e consultável).
- **Docker Compose** para subir o PostgreSQL (e, opcionalmente, os serviços
  Java) com um único comando, sem instalar banco na máquina.
- **Autenticação e controle de acesso** por usuário.
- **Verificação de integridade** por hash (SHA-256) no upload/download.
- **Streaming de upload/download** no gateway web (hoje o gateway carrega o
  arquivo em memória; para arquivos grandes, convém transmitir em blocos).

> Observação sobre WebSocket: ele é um protocolo bidirecional em tempo real,
> ótimo para chat/notificações, mas **não** é o ideal para transferência de
> arquivos grandes — para isso, streaming sobre TCP/HTTP é mais adequado.
> Além disso, "threads" e "WebSocket" resolvem problemas diferentes: threads
> tratam de **concorrência** no servidor; WebSocket é uma **camada de
> transporte** de mensagens.
