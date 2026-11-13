// Pagina do signatario (deep link /assinar/{token}).
// Sem bibliotecas externas: renderiza as paginas como imagens servidas pela API,
// confere o SHA-256 do PDF no navegador e captura a assinatura em <canvas>.
'use strict';

(function () {
  const token = location.pathname.split('/').pop();
  const api = '/sign/' + encodeURIComponent(token);
  const $ = (id) => document.getElementById(id);

  let hashDocumento = null;   // hash conferido (ou informado pelo servidor)
  let hashConfere = false;

  // ---------- utilitarios ----------

  async function erroDe(resposta) {
    try {
      const corpo = await resposta.json();
      return corpo.erro || ('Erro ' + resposta.status);
    } catch (e) {
      return 'Erro ' + resposta.status;
    }
  }

  function mostrarErro(texto) {
    $('carregando').hidden = true;
    const e = $('erro');
    e.textContent = texto;
    e.hidden = false;
  }

  function dataLocal(iso) {
    return iso ? new Date(iso).toLocaleString('pt-BR') : '';
  }

  async function sha256Hex(buffer) {
    const digest = await crypto.subtle.digest('SHA-256', buffer);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0')).join('');
  }

  // ---------- carregamento ----------

  async function carregar() {
    let resposta;
    try {
      resposta = await fetch(api, { cache: 'no-store' });
    } catch (e) {
      mostrarErro('Não foi possível contatar o servidor.');
      return;
    }
    if (!resposta.ok) {
      mostrarErro(await erroDe(resposta));
      return;
    }
    const dados = await resposta.json();
    $('carregando').hidden = true;
    $('convite').hidden = false;

    $('titulo-doc').textContent = dados.envelope.titulo;
    $('saudacao').textContent = 'Olá, ' + dados.signatario.nome + '.';
    $('hash').textContent = dados.envelope.sha256;
    $('baixar').href = api + '/documento';
    hashDocumento = dados.envelope.sha256;

    desenharPaginas(dados);
    await conferirHash(dados.envelope.sha256);

    if (dados.podeAssinar) {
      $('form-assinar').hidden = false;
      $('recusar').hidden = false;
      prepararQuadro();
    } else {
      bloquear(dados.motivo, dados.signatario);
    }
    if (dados.envelope.status === 'COMPLETED') {
      mostrarFinal();
    }
  }

  function mostrarFinal() {
    $('link-final').href = api + '/final';
    $('final').hidden = false;
  }

  function bloquear(motivo, signatario) {
    const b = $('bloqueio');
    let texto = motivo || 'Este convite não está disponível para assinatura.';
    if (signatario && signatario.assinadoEm) {
      texto += ' Assinado em ' + dataLocal(signatario.assinadoEm) + '.';
    }
    b.textContent = texto;
    b.hidden = false;
    $('form-assinar').hidden = true;
    $('recusar').hidden = true;
  }

  function desenharPaginas(dados) {
    const area = $('paginas');
    const total = dados.envelope.paginas.length;
    const campo = dados.signatario.campo;
    dados.envelope.paginas.forEach((p, i) => {
      const numero = i + 1;
      const div = document.createElement('div');
      div.className = 'pagina';
      const img = document.createElement('img');
      img.src = api + '/paginas/' + numero;
      img.alt = 'Página ' + numero + ' de ' + total + ' do documento';
      img.loading = 'lazy';
      img.width = Math.round(p.largura);
      img.height = Math.round(p.altura);
      div.appendChild(img);
      if (campo && campo.pagina === numero) {
        const marca = document.createElement('div');
        marca.className = 'campo';
        marca.textContent = 'Sua assinatura';
        marca.style.left = (campo.x / p.largura * 100) + '%';
        marca.style.bottom = (campo.y / p.altura * 100) + '%';
        marca.style.width = (campo.largura / p.largura * 100) + '%';
        marca.style.height = (campo.altura / p.altura * 100) + '%';
        div.appendChild(marca);
      }
      area.appendChild(div);
    });
  }

  // Baixa o PDF e calcula o SHA-256 localmente: prova que o que foi exibido
  // e o documento cujo hash sera enviado na assinatura.
  async function conferirHash(esperado) {
    const alvo = $('conferencia');
    if (!(window.crypto && crypto.subtle)) {
      alvo.textContent = 'Navegador sem suporte à conferência (use HTTPS). '
        + 'Será usado o hash informado pelo servidor.';
      hashConfere = true;
      return;
    }
    try {
      const r = await fetch(api + '/documento', { cache: 'no-store' });
      if (!r.ok) {
        throw new Error(await erroDe(r));
      }
      const calculado = await sha256Hex(await r.arrayBuffer());
      hashConfere = calculado === esperado;
      hashDocumento = calculado;
      alvo.textContent = hashConfere
        ? 'Conferido: o PDF recebido tem exatamente este hash.'
        : 'ATENÇÃO: o PDF recebido não corresponde ao hash informado.';
      alvo.className = hashConfere ? 'ok' : 'falha';
    } catch (e) {
      hashConfere = false;
      alvo.textContent = 'Falha ao conferir o documento: ' + e.message;
      alvo.className = 'falha';
    }
  }

  // ---------- quadro de assinatura ----------

  let tracos = 0;
  let quadro;
  let ctx;

  function prepararQuadro() {
    quadro = $('quadro');
    ctx = quadro.getContext('2d');
    const escala = Math.min(window.devicePixelRatio || 1, 2);
    const ajustar = () => {
      const r = quadro.getBoundingClientRect();
      quadro.width = Math.round(r.width * escala);
      quadro.height = Math.round(r.height * escala);
      ctx.setTransform(escala, 0, 0, escala, 0, 0);
      ctx.lineWidth = 2.5;
      ctx.lineCap = 'round';
      ctx.lineJoin = 'round';
      ctx.strokeStyle = '#0d1b4c';
      tracos = 0;
    };
    ajustar();
    window.addEventListener('resize', ajustar);

    let desenhando = false;
    const ponto = (ev) => {
      const r = quadro.getBoundingClientRect();
      return { x: ev.clientX - r.left, y: ev.clientY - r.top };
    };
    quadro.addEventListener('pointerdown', (ev) => {
      desenhando = true;
      quadro.setPointerCapture(ev.pointerId);
      const p = ponto(ev);
      ctx.beginPath();
      ctx.moveTo(p.x, p.y);
      ctx.lineTo(p.x + 0.1, p.y + 0.1);
      ctx.stroke();
      ev.preventDefault();
    });
    quadro.addEventListener('pointermove', (ev) => {
      if (!desenhando) return;
      const p = ponto(ev);
      ctx.lineTo(p.x, p.y);
      ctx.stroke();
      tracos++;
      ev.preventDefault();
    });
    const parar = () => { desenhando = false; };
    quadro.addEventListener('pointerup', parar);
    quadro.addEventListener('pointercancel', parar);

    $('limpar').addEventListener('click', () => {
      ctx.save();
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.clearRect(0, 0, quadro.width, quadro.height);
      ctx.restore();
      tracos = 0;
    });
  }

  // ---------- envio ----------

  $('form-assinar').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const status = $('status-assinar');
    const nome = $('nome').value.trim();
    if (!nome) { status.textContent = 'Digite seu nome completo.'; $('nome').focus(); return; }
    if (tracos < 5) { status.textContent = 'Desenhe sua assinatura no quadro.'; return; }
    if (!$('aceite').checked) { status.textContent = 'Marque a caixa de aceite para continuar.'; $('aceite').focus(); return; }
    if (!hashConfere) { status.textContent = 'O documento não pode ser assinado: a conferência do hash falhou.'; return; }

    const botao = $('botao-assinar');
    botao.disabled = true;
    status.textContent = 'Enviando assinatura…';
    const imagem = await new Promise((ok) => quadro.toBlob(ok, 'image/png'));
    const dados = new FormData();
    dados.append('nome', nome);
    dados.append('sha256', hashDocumento);
    dados.append('aceite', 'true');
    dados.append('assinatura', imagem, 'assinatura.png');
    try {
      const r = await fetch(api, { method: 'POST', body: dados });
      if (!r.ok) {
        status.textContent = await erroDe(r);
        botao.disabled = false;
        return;
      }
      const res = await r.json();
      status.textContent = '';
      bloquear('Documento assinado com sucesso. Obrigado!', res.signatario);
      if (res.finalizado) {
        mostrarFinal();
      }
    } catch (e) {
      status.textContent = 'Falha de conexão. Tente novamente.';
      botao.disabled = false;
    }
  });

  $('form-recusar').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const status = $('status-recusar');
    const motivo = $('motivo').value.trim();
    if (!motivo) { status.textContent = 'Informe o motivo da recusa.'; $('motivo').focus(); return; }
    const corpo = new URLSearchParams({ motivo });
    try {
      const r = await fetch(api + '/decline', { method: 'POST', body: corpo });
      if (!r.ok) {
        status.textContent = await erroDe(r);
        return;
      }
      bloquear('Você recusou a assinatura deste documento.', null);
    } catch (e) {
      status.textContent = 'Falha de conexão. Tente novamente.';
    }
  });

  carregar();
}());
