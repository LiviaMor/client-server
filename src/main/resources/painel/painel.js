'use strict';

/* Angel Signature — painel do emissor.
 * Todo texto vindo do servidor entra no DOM via textContent (nunca innerHTML). */

const CHAVE_SESSAO = 'angel-admin-key';
let chave = sessionStorage.getItem(CHAVE_SESSAO) || '';
let envelopes = [];

const $ = (seletor, raiz = document) => raiz.querySelector(seletor);

function el(tag, props = {}, ...filhos) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') n.className = v;
    else if (k === 'text') n.textContent = v;
    else if (k.startsWith('on')) n.addEventListener(k.slice(2), v);
    else n.setAttribute(k, v === true ? '' : v);
  }
  for (const f of filhos.flat()) {
    if (f !== null && f !== undefined && f !== false) n.append(f);
  }
  return n;
}

/* ----- Rótulos ----- */

const STATUS_ENVELOPE = {
  DRAFT: ['Rascunho', 'rascunho'],
  SENT: ['Aguardando assinaturas', 'enviado'],
  COMPLETED: ['Concluído', 'concluido'],
  DECLINED: ['Recusado', 'recusado'],
  EXPIRED: ['Expirado', 'aviso'],
  CANCELED: ['Cancelado', 'aviso'],
};
const STATUS_SIGNATARIO = {
  PENDING: ['Pendente', 'rascunho'],
  VIEWED: ['Visualizou', 'enviado'],
  SIGNED: ['Assinou', 'concluido'],
  DECLINED: ['Recusou', 'recusado'],
};
const EVENTOS = {
  'envelope.created': ['Documento criado', ''],
  'signer.added': ['Signatário adicionado', ''],
  'envelope.sent': ['Convites enviados', ''],
  'envelope.resent': ['Convites reenviados', ''],
  'email.sent': ['E-mail enviado', ''],
  'email.failed': ['Falha no envio de e-mail', 'falha'],
  'signer.viewed': ['Documento visualizado', ''],
  'signer.signed': ['Documento assinado', 'sucesso'],
  'signer.declined': ['Assinatura recusada', 'falha'],
  'envelope.completed': ['Documento finalizado e selado (PAdES)', 'sucesso'],
  'envelope.finalize_failed': ['Falha ao finalizar', 'falha'],
  'envelope.published': ['Publicado no servidor SiCA', ''],
  'email.final_sent': ['PDF final enviado por e-mail', ''],
  'email.final_failed': ['Falha ao enviar o PDF final', 'falha'],
};

function badge([texto, classe]) {
  return el('span', { class: 'badge ' + classe, text: texto });
}
function badgeEnvelope(s) { return badge(STATUS_ENVELOPE[s] || [s, 'rascunho']); }
function badgeSignatario(s) { return badge(STATUS_SIGNATARIO[s] || [s, 'rascunho']); }

function dataHora(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d) ? iso : d.toLocaleString('pt-BR');
}
function tamanho(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
function linhaVazia(colunas, texto) {
  return el('tr', {}, el('td', { class: 'vazio', colspan: String(colunas), text: texto }));
}

/* ----- Rede ----- */

async function erroDe(r) {
  const corpo = await r.text();
  try {
    const j = JSON.parse(corpo);
    if (j && j.erro) return j.erro;
  } catch { /* corpo nao e JSON */ }
  return corpo || ('Erro ' + r.status);
}

async function chamar(caminho, opcoes = {}) {
  const r = await fetch(caminho, {
    ...opcoes,
    headers: { ...(opcoes.headers || {}), 'X-Admin-Key': chave },
  });
  if (r.status === 401) {
    sair('Chave inválida ou expirada. Entre novamente.');
    throw new Error('Não autorizado.');
  }
  if (!r.ok) throw new Error(await erroDe(r));
  return r;
}

async function baixar(caminho, nomeArquivo) {
  const r = await chamar(caminho);
  const url = URL.createObjectURL(await r.blob());
  const a = el('a', { href: url, download: nomeArquivo });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

function nomeSeguro(titulo) {
  return (titulo || 'documento').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '').toLowerCase() || 'documento';
}

function mensagem(elemento, texto, tipo = '') {
  elemento.hidden = !texto;
  elemento.textContent = texto || '';
  elemento.className = 'msg' + (tipo ? ' ' + tipo : '');
}

/* ----- Entrada / saída ----- */

function entrar() {
  $('#topo').hidden = false;
  $('#login').hidden = true;
  $('#app').hidden = false;
  $('#abas').hidden = false;
  $('#sair').hidden = false;
  mostrarAba('arquivos');
  carregarArquivos();
  carregarEnvelopes();
}

function sair(aviso) {
  chave = '';
  sessionStorage.removeItem(CHAVE_SESSAO);
  $('#topo').hidden = true;
  $('#app').hidden = true;
  $('#abas').hidden = true;
  $('#sair').hidden = true;
  $('#login').hidden = false;
  $('#chave').value = '';
  mensagem($('#erro-login'), aviso || '', 'erro');
}

/** A chave vale se o servidor nao responder 401 (ele pode estar sem o SiCA TCP, o que da 502). */
async function chaveValida(candidata) {
  const r = await fetch('/api/list', { headers: { 'X-Admin-Key': candidata } });
  return r.status !== 401;
}

$('#form-login').addEventListener('submit', async (e) => {
  e.preventDefault();
  const tentativa = $('#chave').value.trim();
  if (!tentativa) {
    mensagem($('#erro-login'), 'Informe a chave de administrador.', 'erro');
    return;
  }
  try {
    if (!(await chaveValida(tentativa))) {
      mensagem($('#erro-login'), 'Chave de administrador inválida.', 'erro');
      return;
    }
    chave = tentativa;
    sessionStorage.setItem(CHAVE_SESSAO, chave);
    mensagem($('#erro-login'), '');
    entrar();
  } catch {
    mensagem($('#erro-login'), 'Não foi possível contatar o servidor.', 'erro');
  }
});
$('#sair').addEventListener('click', () => sair(''));

/* ----- Abas ----- */

function mostrarAba(nome) {
  for (const b of document.querySelectorAll('.aba')) {
    b.setAttribute('aria-selected', String(b.dataset.aba === nome));
  }
  for (const s of document.querySelectorAll('[data-painel]')) {
    s.hidden = s.dataset.painel !== nome;
  }
}
for (const b of document.querySelectorAll('.aba')) {
  b.addEventListener('click', () => mostrarAba(b.dataset.aba));
}

/* ----- Compartilhamento de arquivos (SiCA) ----- */

async function carregarArquivos() {
  const corpo = $('#lista-arquivos');
  try {
    const arquivos = await (await chamar('/api/list')).json();
    corpo.replaceChildren();
    if (arquivos.length === 0) corpo.append(linhaVazia(3, 'Nenhum arquivo no servidor.'));
    for (const a of arquivos) {
      corpo.append(el('tr', {},
        el('td', { text: a.nome }),
        el('td', { class: 'dir', text: tamanho(a.tamanho) }),
        el('td', { class: 'dir' },
          el('button', { type: 'button', class: 'botao outline sm', text: 'Baixar',
            onclick: () => baixar('/api/download?nome=' + encodeURIComponent(a.nome), a.nome)
              .catch((err) => avisar(err.message)) }))));
    }
  } catch (err) {
    corpo.replaceChildren(linhaVazia(3, 'Não foi possível listar: ' + err.message));
  }
}

$('#form-upload').addEventListener('submit', async (e) => {
  e.preventDefault();
  const arquivo = $('#arquivo-sica').files[0];
  const status = $('#status-upload');
  if (!arquivo) { mensagem(status, 'Escolha um arquivo.', 'erro'); return; }
  const fd = new FormData();
  fd.append('arquivo', arquivo);
  mensagem(status, 'Enviando…');
  try {
    const r = await chamar('/api/upload', { method: 'POST', body: fd });
    mensagem(status, (await r.text()) || 'Arquivo enviado.', 'ok');
    $('#form-upload').reset();
    carregarArquivos();
  } catch (err) {
    mensagem(status, err.message, 'erro');
  }
});
$('#atualizar-arquivos').addEventListener('click', carregarArquivos);

function avisar(texto) {
  const a = $('#aviso-global');
  mensagem(a, texto);
  if (texto) setTimeout(() => { if (a.textContent === texto) mensagem(a, ''); }, 8000);
}

/* ----- Novo documento para assinatura ----- */

function campoPosicao(nome, rotulo, valor) {
  const id = 'pos-' + nome + '-' + Math.random().toString(36).slice(2, 8);
  return el('div', {},
    el('label', { class: 'rotulo', for: id, text: rotulo }),
    el('input', { id, class: 'campo', type: 'number', step: 'any', min: '0', 'data-campo': nome, value: String(valor), required: true }));
}

function novoSignatario() {
  const lista = $('#signatarios');
  const i = lista.children.length;
  const idNome = 'sig-nome-' + Math.random().toString(36).slice(2, 8);
  const idEmail = 'sig-email-' + Math.random().toString(36).slice(2, 8);
  const bloco = el('div', { class: 'signatario' },
    el('div', { class: 'grade' },
      el('div', {},
        el('label', { class: 'rotulo', for: idNome, text: 'Nome completo' }),
        el('input', { id: idNome, class: 'campo', type: 'text', maxlength: '200', 'data-campo': 'nome', required: true })),
      el('div', {},
        el('label', { class: 'rotulo', for: idEmail, text: 'E-mail' }),
        el('input', { id: idEmail, class: 'campo', type: 'email', maxlength: '254', 'data-campo': 'email', required: true }))),
    el('details', {},
      el('summary', { text: 'Posição da assinatura no PDF (pontos, origem no canto inferior esquerdo)' }),
      el('div', { class: 'posicao' },
        campoPosicao('pagina', 'Página', 1),
        campoPosicao('x', 'X', 72),
        campoPosicao('y', 'Y', 100 + 70 * i),
        campoPosicao('largura', 'Largura', 200),
        campoPosicao('altura', 'Altura', 60))),
    el('button', { type: 'button', class: 'botao outline sm remover', text: 'Remover',
      onclick: () => { if (lista.children.length > 1) bloco.remove(); } }));
  lista.append(bloco);
}
$('#add-signatario').addEventListener('click', novoSignatario);

function valorDe(bloco, campo) {
  return bloco.querySelector('[data-campo="' + campo + '"]').value.trim();
}

$('#form-envelope').addEventListener('submit', async (e) => {
  e.preventDefault();
  const status = $('#status-envelope');
  const botao = $('#botao-envelope');
  const titulo = $('#titulo-doc').value.trim();
  const pdf = $('#pdf-doc').files[0];
  const blocos = [...document.querySelectorAll('#signatarios .signatario')];
  if (!titulo || !pdf) { mensagem(status, 'Informe o título e escolha o PDF.', 'erro'); return; }
  for (const b of blocos) {
    if (!valorDe(b, 'nome') || !valorDe(b, 'email')) {
      mensagem(status, 'Preencha nome e e-mail de todos os signatários.', 'erro');
      return;
    }
  }
  botao.disabled = true;
  let envelope = null;
  try {
    mensagem(status, 'Criando o documento…');
    const fd = new FormData();
    fd.append('titulo', titulo);
    fd.append('arquivo', pdf);
    envelope = await (await chamar('/envelopes', { method: 'POST', body: fd })).json();

    for (const [i, b] of blocos.entries()) {
      mensagem(status, 'Adicionando signatário ' + (i + 1) + ' de ' + blocos.length + '…');
      const p = new URLSearchParams();
      for (const c of ['nome', 'email', 'pagina', 'x', 'y', 'largura', 'altura']) p.set(c, valorDe(b, c));
      await chamar('/envelopes/' + envelope.id + '/signers', { method: 'POST', body: p });
    }

    mensagem(status, 'Enviando convites…');
    const envio = await (await chamar('/envelopes/' + envelope.id + '/send', { method: 'POST' })).json();
    const falhas = envio.convites.filter((c) => !c.enviado);
    if (falhas.length === 0) {
      mensagem(status, 'Convites enviados a ' + envio.convites.length + ' signatário(s). ' +
        'Com o e-mail em modo "console", o link aparece no terminal do servidor.', 'ok');
    } else {
      mensagem(status, 'Documento criado, mas ' + falhas.length + ' e-mail(s) falharam: ' +
        falhas.map((f) => f.email + ' (' + f.erro + ')').join('; ') +
        '. Use "Reenviar convites" na lista.', 'erro');
    }
    $('#form-envelope').reset();
    $('#signatarios').replaceChildren();
    novoSignatario();
  } catch (err) {
    mensagem(status, err.message + (envelope
      ? ' O documento foi criado como rascunho; conclua pela lista abaixo.' : ''), 'erro');
  } finally {
    botao.disabled = false;
    carregarEnvelopes();
  }
});

/* ----- Lista de documentos ----- */

async function carregarEnvelopes() {
  try {
    envelopes = (await (await chamar('/envelopes')).json()).envelopes;
  } catch (err) {
    envelopes = [];
    const msg = 'Não foi possível carregar os documentos: ' + err.message;
    $('#lista-envelopes').replaceChildren(linhaVazia(5, msg));
    $('#lista-auditoria').replaceChildren(linhaVazia(5, msg));
    return;
  }
  desenharEnvelopes();
  desenharAuditoria();
}

function assinaram(env) { return env.signatarios.filter((s) => s.status === 'SIGNED').length; }

function desenharEnvelopes() {
  const corpo = $('#lista-envelopes');
  corpo.replaceChildren();
  if (envelopes.length === 0) corpo.append(linhaVazia(5, 'Nenhum documento enviado ainda.'));
  for (const env of envelopes) {
    const acoes = el('div', { class: 'acoes' });
    if (env.status === 'DRAFT' || env.status === 'SENT') {
      acoes.append(el('button', { type: 'button', class: 'botao outline sm',
        text: env.status === 'DRAFT' ? 'Enviar convites' : 'Reenviar convites',
        onclick: (e) => reenviar(env, e.currentTarget) }));
    }
    if (env.status === 'SENT' && env.signatarios.length > 0 && assinaram(env) === env.signatarios.length) {
      acoes.append(el('button', { type: 'button', class: 'botao outline sm', text: 'Finalizar',
        onclick: (e) => finalizar(env, e.currentTarget) }));
    }
    if (env.status === 'COMPLETED') {
      acoes.append(el('button', { type: 'button', class: 'botao sm', text: 'Baixar PDF assinado',
        onclick: () => baixarFinal(env) }));
    }
    acoes.append(el('button', { type: 'button', class: 'botao outline sm', text: 'Auditoria',
      onclick: () => { mostrarAba('auditoria'); abrirDetalhe(env.id); } }));

    corpo.append(el('tr', {},
      el('td', {}, el('div', { class: 'nome-doc', text: env.titulo }),
        el('div', { class: 'mini', text: env.signatarios.length + ' signatário(s)' })),
      el('td', {}, badgeEnvelope(env.status)),
      el('td', { text: assinaram(env) + ' de ' + env.signatarios.length }),
      el('td', { text: dataHora(env.criadoEm) }),
      el('td', { class: 'dir' }, acoes)));
  }
}

async function reenviar(env, botao) {
  botao.disabled = true;
  try {
    const envio = await (await chamar('/envelopes/' + env.id + '/send', { method: 'POST' })).json();
    const falhas = envio.convites.filter((c) => !c.enviado).length;
    avisar(falhas === 0 ? 'Convites enviados.' : falhas + ' e-mail(s) falharam. Veja a auditoria.');
  } catch (err) {
    avisar(err.message);
  } finally {
    carregarEnvelopes();
  }
}

async function finalizar(env, botao) {
  botao.disabled = true;
  try {
    await chamar('/envelopes/' + env.id + '/finalize', { method: 'POST' });
    avisar('Documento finalizado e selado.');
  } catch (err) {
    avisar('Não foi possível finalizar: ' + err.message);
  } finally {
    carregarEnvelopes();
  }
}

function baixarFinal(env) {
  baixar('/envelopes/' + env.id + '/download', nomeSeguro(env.titulo) + '-assinado.pdf')
    .catch((err) => avisar(err.message));
}

$('#atualizar-envelopes').addEventListener('click', carregarEnvelopes);

/* ----- Auditoria ----- */

function indicador(valor, nome) {
  return el('div', { class: 'indicador' },
    el('div', { class: 'valor', text: String(valor) }), el('div', { class: 'nome', text: nome }));
}

function desenharAuditoria() {
  const total = envelopes.length;
  const andamento = envelopes.filter((e) => e.status === 'SENT').length;
  const concluidos = envelopes.filter((e) => e.status === 'COMPLETED').length;
  const encerrados = envelopes.filter((e) => ['DECLINED', 'EXPIRED', 'CANCELED'].includes(e.status)).length;
  $('#resumo').replaceChildren(
    indicador(total, 'Documentos enviados'),
    indicador(andamento, 'Aguardando assinaturas'),
    indicador(concluidos, 'Concluídos'),
    indicador(encerrados, 'Recusados / encerrados'));

  const corpo = $('#lista-auditoria');
  corpo.replaceChildren();
  if (total === 0) corpo.append(linhaVazia(5, 'Nenhum documento enviado ainda.'));
  for (const env of envelopes) {
    const quem = env.signatarios.filter((s) => s.status === 'SIGNED').map((s) => s.nome);
    corpo.append(el('tr', {},
      el('td', {}, el('div', { class: 'nome-doc', text: env.titulo })),
      el('td', {}, badgeEnvelope(env.status)),
      el('td', {},
        el('div', { text: assinaram(env) + ' de ' + env.signatarios.length + ' assinaram' }),
        el('div', { class: 'mini', text: quem.length ? quem.join(', ') : 'Ninguém assinou ainda' })),
      el('td', { text: dataHora(env.criadoEm) }),
      el('td', { class: 'dir' },
        el('button', { type: 'button', class: 'botao outline sm', text: 'Ver detalhes',
          onclick: () => abrirDetalhe(env.id) }))));
  }
}

async function abrirDetalhe(id) {
  const painel = $('#detalhe');
  painel.hidden = false;
  painel.replaceChildren(el('div', { class: 'cartao-corpo', text: 'Carregando…' }));
  try {
    const [env, aud] = await Promise.all([
      chamar('/envelopes/' + id).then((r) => r.json()),
      chamar('/envelopes/' + id + '/audit').then((r) => r.json()),
    ]);
    desenharDetalhe(painel, env, aud.eventos);
    painel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  } catch (err) {
    painel.replaceChildren(el('div', { class: 'cartao-corpo' }, el('p', { class: 'msg erro', text: err.message })));
  }
}

function desenharDetalhe(painel, env, eventos) {
  const nomes = Object.fromEntries(env.signatarios.map((s) => [s.id, s.nome]));

  const signatarios = el('table', { class: 'tabela' },
    el('thead', {}, el('tr', {},
      el('th', { text: 'Signatário' }), el('th', { text: 'E-mail' }),
      el('th', { text: 'Situação' }), el('th', { text: 'Assinou em' }))),
    el('tbody', {}, env.signatarios.map((s) => el('tr', {},
      el('td', { text: s.nome }), el('td', { text: s.email }),
      el('td', {}, badgeSignatario(s.status)), el('td', { text: dataHora(s.assinadoEm) })))));

  const linha = el('ol', { class: 'linha-tempo' }, eventos.map((ev) => {
    const [titulo, classe] = EVENTOS[ev.tipo] || [ev.tipo, ''];
    const quem = ev.signerId && nomes[ev.signerId] ? nomes[ev.signerId] : null;
    const meta = [dataHora(ev.ocorridoEm)];
    if (quem) meta.push('Signatário: ' + quem);
    if (ev.ip) meta.push('IP ' + ev.ip);
    if (ev.userAgent) meta.push(ev.userAgent);
    return el('li', { class: classe },
      el('div', { class: 'evento-titulo', text: titulo }),
      el('div', { class: 'evento-meta', text: meta.join(' · ') }),
      ev.sha256Documento ? el('div', { class: 'evento-meta' }, 'SHA-256: ', el('code', { text: ev.sha256Documento })) : null,
      ev.detalhe ? el('div', { class: 'evento-meta', text: ev.detalhe }) : null);
  }));

  painel.replaceChildren(
    el('div', { class: 'cartao-topo linha entre' },
      el('div', { class: 'linha' }, el('h2', { class: 'titulo', text: env.titulo }), badgeEnvelope(env.status)),
      el('div', { class: 'acoes' },
        env.status === 'COMPLETED'
          ? el('button', { type: 'button', class: 'botao sm', text: 'Baixar PDF assinado', onclick: () => baixarFinal(env) })
          : null,
        el('button', { type: 'button', class: 'botao outline sm', text: 'Fechar', onclick: () => { painel.hidden = true; } }))),
    el('div', { class: 'cartao-corpo' },
      el('dl', { class: 'dados' },
        el('dt', { text: 'Identificador' }), el('dd', {}, el('code', { text: env.id })),
        el('dt', { text: 'Criado em' }), el('dd', { text: dataHora(env.criadoEm) }),
        el('dt', { text: 'Concluído em' }), el('dd', { text: dataHora(env.concluidoEm) }),
        el('dt', { text: 'SHA-256 original' }), el('dd', {}, el('code', { text: env.sha256Original || '—' })),
        el('dt', { text: 'SHA-256 final' }), el('dd', {}, el('code', { text: env.sha256Final || '—' })))),
    el('div', { class: 'cartao-topo' }, el('h3', { class: 'subtitulo', text: 'Quem assinou' })),
    el('div', { class: 'tabela-wrap' }, signatarios),
    el('div', { class: 'cartao-topo' }, el('h3', { class: 'subtitulo', text: 'Trilha de auditoria (imutável)' })),
    el('div', { class: 'cartao-corpo' }, linha));
}

/* ----- Início ----- */

novoSignatario();
if (chave) {
  chaveValida(chave).then((ok) => (ok ? entrar() : sair(''))).catch(() => sair(''));
}
