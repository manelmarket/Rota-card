/*
 * PAINEL DE ACESSOS - ROTA CARD
 * Google Apps Script + Planilha Google (gratis).
 * Cole este arquivo inteiro em Extensoes > Apps Script (apague o que ja estiver la).
 *
 * Antes de implantar:
 *  1) Configuracoes do projeto (engrenagem) > Propriedades do script > adicione ADMIN_KEY = a senha do SEU painel.
 *  2) Execute a funcao "configurar" uma vez (e autorize).
 *  3) Implantar > Nova implantacao > App da Web > Executar como: Eu > Quem tem acesso: Qualquer pessoa.
 * Depois de editar este codigo, faca Implantar > Gerenciar implantacoes > editar > Nova versao.
 */

const ABA = 'usuarios';
const ABA_LOG = 'registro';
const COLS = ['usuario', 'senhaHash', 'nome', 'criadoEm', 'dias', 'contar', 'expiraEm', 'expiraEmTexto',
  'ativo', 'dispositivo', 'token', 'ultimoAcesso', 'aviso', 'versao', 'ofertasHoje', 'aceitasHoje', 'diaStats'];
const DIA = 24 * 60 * 60 * 1000;
const LINK_APP = 'https://github.com/manelmarket/Rota-card/releases/latest';

/* ------------------------------------------------------------------ planilha */

function sheet_() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sh = ss.getSheetByName(ABA);
  if (!sh) sh = ss.insertSheet(ABA);
  if (sh.getLastRow() === 0) {
    sh.getRange(1, 1, 1, COLS.length).setValues([COLS]);
    sh.setFrozenRows(1);
  }
  return sh;
}

function configurar() {
  const sh = sheet_();
  sh.getRange(1, 1, 1, COLS.length).setValues([COLS]);
  sh.getRange('A2:B2000').setNumberFormat('@');
  sh.getRange('J2:K2000').setNumberFormat('@');
  salt_();
  const k = PropertiesService.getScriptProperties().getProperty('ADMIN_KEY');
  Logger.log(k ? 'Pronto. Planilha configurada e ADMIN_KEY encontrada.'
    : 'ATENCAO: crie a propriedade ADMIN_KEY (Configuracoes do projeto > Propriedades do script).');
}

function readAll_() {
  const sh = sheet_();
  const n = sh.getLastRow();
  if (n < 2) return [];
  const vals = sh.getRange(2, 1, n - 1, COLS.length).getValues();
  return vals.map(function (v, i) { return rowObj_(v, i + 2); });
}

function rowObj_(v, row) {
  const o = { _row: row };
  COLS.forEach(function (c, j) { o[c] = v[j]; });
  o.usuario = String(o.usuario);
  o.dispositivo = String(o.dispositivo || '');
  o.token = String(o.token || '');
  return o;
}

/* Acha o usuario pela coluna A sem ler a planilha inteira (rapido mesmo com muitos usuarios). */
function find_(u) {
  u = String(u || '');
  if (!u) return null;
  const sh = sheet_();
  const n = sh.getLastRow();
  if (n < 2) return null;
  const cell = sh.getRange(2, 1, n - 1, 1).createTextFinder(u).matchEntireCell(true).matchCase(true).findNext();
  if (!cell) return null;
  const r = cell.getRow();
  const o = rowObj_(sh.getRange(r, 1, 1, COLS.length).getValues()[0], r);
  return o.usuario === u ? o : null;
}

/* Registro das acoes do administrador (aba "registro"). Nunca grava senhas. */
function audit_(action, d) {
  try {
    const ss = SpreadsheetApp.getActiveSpreadsheet();
    let sh = ss.getSheetByName(ABA_LOG);
    if (!sh) {
      sh = ss.insertSheet(ABA_LOG);
      sh.getRange(1, 1, 1, 4).setValues([['quando', 'acao', 'usuario', 'detalhe']]);
      sh.setFrozenRows(1);
    }
    const copy = {};
    Object.keys(d || {}).forEach(function (k) { if (k !== 'p' && k !== 'u') copy[k] = d[k]; });
    sh.appendRow([fmt_(Date.now()), action, String((d && d.u) || ''), JSON.stringify(copy).slice(0, 300)]);
    // guarda so os ultimos 2000 registros
    const extra = sh.getLastRow() - 2001;
    if (extra > 0) sh.deleteRows(2, extra);
  } catch (e) { }
}

function save_(o) {
  sheet_().getRange(o._row, 1, 1, COLS.length).setValues([COLS.map(function (c) {
    return o[c] === undefined ? '' : o[c];
  })]);
}

function append_(o) {
  const sh = sheet_();
  const r = sh.getLastRow() + 1;
  sh.getRange(r, 1, 1, 2).setNumberFormat('@');
  sh.getRange(r, 10, 1, 2).setNumberFormat('@');
  o._row = r;
  save_(o);
}

/* ------------------------------------------------------------------ utilitarios */

function salt_() {
  const p = PropertiesService.getScriptProperties();
  let s = p.getProperty('SALT');
  if (!s) { s = Utilities.getUuid(); p.setProperty('SALT', s); }
  return s;
}

function hash_(u, p) {
  const raw = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256,
    salt_() + '|' + u + '|' + p, Utilities.Charset.UTF_8);
  return raw.map(function (b) { return ('0' + (b & 0xFF).toString(16)).slice(-2); }).join('');
}

function fmt_(ms) {
  return Utilities.formatDate(new Date(Number(ms)), Session.getScriptTimeZone(), 'dd/MM/yyyy HH:mm');
}

function isActive_(o) {
  return o.ativo === true || String(o.ativo).toUpperCase() === 'TRUE';
}

function tooMany_(key, max) {
  return Number(CacheService.getScriptCache().get(key) || 0) >= max;
}
function addFail_(key) {
  const c = CacheService.getScriptCache();
  c.put(key, String(Number(c.get(key) || 0) + 1), 900);
}
function clearFail_(key) { CacheService.getScriptCache().remove(key); }

function fail_(code, msg) { return { ok: false, code: code, msg: msg }; }

/* Configuracao global (vale para todos): aviso, versao minima e valores padrao */
function cfg_() {
  const p = PropertiesService.getScriptProperties();
  let d = {};
  try { d = JSON.parse(p.getProperty('DEFAULTS') || '{}'); } catch (e) { d = {}; }
  return { aviso: p.getProperty('AVISO') || '', minVer: Number(p.getProperty('MIN_VER') || 0), defaults: d };
}

function extra_(row) {
  const c = cfg_();
  return { notice: row.aviso ? String(row.aviso) : c.aviso, minVer: c.minVer, defaults: c.defaults };
}

function verCheck_(req) {
  const mv = cfg_().minVer;
  if (mv && Number(req.ver || 0) < mv) {
    const r = fail_('update', 'Atualize o aplicativo para continuar (versão mínima ' + mv + ').');
    r.minVer = mv;
    return r;
  }
  return null;
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

/* ------------------------------------------------------------------ API do aplicativo */

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    return json_(handle_(req));
  } catch (err) {
    return json_(fail_('error', 'Erro no servidor. Tente novamente.'));
  }
}

function handle_(req) {
  const now = Date.now();
  const u = String(req.u || '').replace(/\D/g, '');
  const dev = String(req.dev || '');

  if (req.action === 'login') {
    if (!u || !req.p) return fail_('badpass', 'Informe usuário e senha.');
    const fk = 'f:' + u;
    if (tooMany_(fk, 5)) return fail_('locked', 'Muitas tentativas. Aguarde 15 minutos.');
    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      const row = find_(u);
      if (!row || row.senhaHash !== hash_(u, String(req.p))) {
        addFail_(fk);
        return fail_('badpass', 'Usuário ou senha incorretos.');
      }
      clearFail_(fk);
      const vc = verCheck_(req);
      if (vc) return vc;
      if (!isActive_(row)) return fail_('blocked', 'Acesso bloqueado. Fale com o administrador.');
      if (row.dispositivo && row.dispositivo !== dev) {
        return fail_('device', 'Este usuário já está ligado a outro aparelho. Peça ao administrador para liberar.');
      }
      if (!row.expiraEm && row.contar === 'primeiro') {
        row.expiraEm = now + Number(row.dias || 0) * DIA;
        row.expiraEmTexto = fmt_(row.expiraEm);
      }
      if (!row.expiraEm || Number(row.expiraEm) <= now) {
        return fail_('expired', 'Seu acesso venceu' + (row.expiraEm ? ' em ' + fmt_(row.expiraEm) : '') +
          '. Fale com o administrador.');
      }
      row.dispositivo = dev;
      row.token = Utilities.getUuid();
      row.ultimoAcesso = now;
      save_(row);
      return Object.assign({ ok: true, token: row.token, exp: Number(row.expiraEm), now: now, name: row.nome || '' }, extra_(row));
    } finally {
      lock.releaseLock();
    }
  }

  if (req.action === 'check') {
    const row = find_(u);
    if (!row) return fail_('nouser', 'Usuário não encontrado.');
    if (!isActive_(row)) return fail_('blocked', 'Acesso bloqueado. Fale com o administrador.');
    const vc2 = verCheck_(req);
    if (vc2) return vc2;
    if (!row.token || row.token !== String(req.token || '')) {
      return fail_('token', 'Sua sessão foi encerrada. Entre novamente.');
    }
    if (row.dispositivo && row.dispositivo !== dev) {
      return fail_('device', 'Este usuário está ligado a outro aparelho.');
    }
    if (!row.expiraEm || Number(row.expiraEm) <= now) {
      return fail_('expired', 'Seu acesso venceu. Fale com o administrador.');
    }
    if (req.offers !== undefined) {
      row.versao = String(req.vname || req.ver || '');
      row.ofertasHoje = Number(req.offers) || 0;
      row.aceitasHoje = Number(req.acc) || 0;
      row.diaStats = Utilities.formatDate(new Date(now), Session.getScriptTimeZone(), 'yyyy-MM-dd');
      row.ultimoAcesso = now;
      save_(row);
    } else if (now - (Number(row.ultimoAcesso) || 0) > 10 * 60 * 1000) {
      row.ultimoAcesso = now;
      save_(row);
    }
    return Object.assign({ ok: true, exp: Number(row.expiraEm), now: now, name: row.nome || '' }, extra_(row));
  }

  return fail_('error', 'Pedido inválido.');
}

/* ------------------------------------------------------------------ painel do administrador */

function doGet() {
  return HtmlService.createHtmlOutput(PAGINA.replace('__LINK__', LINK_APP))
    .setTitle('Painel de acessos')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

function pub_(o, now) {
  const exp = o.expiraEm ? Number(o.expiraEm) : 0;
  const ativo = isActive_(o);
  let status;
  if (!ativo) status = 'bloqueado';
  else if (!exp) status = 'aguardando';
  else if (exp <= now) status = 'vencido';
  else status = (exp - now) <= 3 * DIA ? 'vencendo' : 'ativo';
  return {
    usuario: o.usuario, nome: o.nome || '', criadoEm: Number(o.criadoEm) || 0,
    dias: Number(o.dias) || 0, contar: o.contar, expiraEm: exp, ativo: ativo, status: status,
    diasRestantes: exp ? Math.ceil((exp - now) / DIA) : null,
    aparelho: o.dispositivo ? String(o.dispositivo).slice(0, 6).toUpperCase() : '',
    ultimoAcesso: Number(o.ultimoAcesso) || 0,
    aviso: o.aviso ? String(o.aviso) : '', versao: o.versao ? String(o.versao) : '',
    ofertasHoje: Number(o.ofertasHoje) || 0, aceitasHoje: Number(o.aceitasHoje) || 0,
    diaStats: o.diaStats ? String(o.diaStats) : ''
  };
}

function adminApi(key, action, d) {
  const real = PropertiesService.getScriptProperties().getProperty('ADMIN_KEY');
  if (!real) return fail_('nokey', 'Defina ADMIN_KEY nas propriedades do script.');
  if (tooMany_('adminfail', 8)) return fail_('locked', 'Muitas tentativas. Aguarde 15 minutos.');
  if (String(key) !== real) { addFail_('adminfail'); return fail_('badkey', 'Senha do painel incorreta.'); }
  clearFail_('adminfail');
  d = d || {};
  const now = Date.now();
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  if (action !== 'list' && action !== 'getConfig') audit_(action, d);
  try {
    if (action === 'list') {
      const users = readAll_().map(function (o) { return pub_(o, now); });
      users.sort(function (a, b) { return b.criadoEm - a.criadoEm; });
      return { ok: true, now: now, users: users,
        today: Utilities.formatDate(new Date(now), Session.getScriptTimeZone(), 'yyyy-MM-dd') };
    }

    if (action === 'getConfig') {
      return { ok: true, cfg: cfg_() };
    }

    if (action === 'setConfig') {
      const pr = PropertiesService.getScriptProperties();
      pr.setProperty('AVISO', String(d.aviso || '').slice(0, 300));
      pr.setProperty('MIN_VER', String(Math.max(0, parseInt(d.minVer, 10) || 0)));
      const def = {};
      ['green', 'yellow', 'minHour', 'maxPickup', 'minRating'].forEach(function (k) {
        const v = parseFloat(String((d.defaults || {})[k]).replace(',', '.'));
        if (!isNaN(v) && v > 0) def[k] = v;
      });
      pr.setProperty('DEFAULTS', JSON.stringify(def));
      return { ok: true };
    }

    if (action === 'create') {
      const u = String(d.u || '').replace(/\D/g, '');
      const p = String(d.p || '').replace(/\D/g, '');
      const dias = Math.max(0, Math.min(3650, parseInt(d.dias, 10) || 0));
      if (u.length < 4 || u.length > 12) return fail_('erro', 'O usuário deve ter de 4 a 12 números.');
      if (p.length < 4 || p.length > 12) return fail_('erro', 'A senha deve ter de 4 a 12 números.');
      if (find_(u)) return fail_('erro', 'Esse usuário já existe.');
      const contar = d.contar === 'primeiro' ? 'primeiro' : 'hoje';
      const o = {
        usuario: u, senhaHash: hash_(u, p), nome: String(d.nome || '').slice(0, 40),
        criadoEm: now, dias: dias, contar: contar,
        expiraEm: contar === 'hoje' ? now + dias * DIA : '', ativo: true,
        dispositivo: '', token: '', ultimoAcesso: ''
      };
      o.expiraEmTexto = o.expiraEm ? fmt_(o.expiraEm) : '(aguardando 1º acesso)';
      append_(o);
      return { ok: true, u: u, p: p, dias: dias, contar: contar };
    }

    const row = find_(d.u);
    if (!row) return fail_('erro', 'Usuário não encontrado.');

    if (action === 'addDays') {
      const n = parseInt(d.n, 10) || 0;
      if (!row.expiraEm) {
        row.dias = Math.max(0, (Number(row.dias) || 0) + n);
      } else {
        const base = n > 0 ? Math.max(Number(row.expiraEm), now) : Number(row.expiraEm);
        row.expiraEm = base + n * DIA;
        row.expiraEmTexto = fmt_(row.expiraEm);
      }
    } else if (action === 'setDays') {
      const n = Math.max(0, Math.min(3650, parseInt(d.n, 10) || 0));
      row.expiraEm = now + n * DIA;
      row.expiraEmTexto = fmt_(row.expiraEm);
      row.contar = 'hoje';
    } else if (action === 'toggle') {
      row.ativo = !isActive_(row);
      if (!row.ativo) row.token = '';
    } else if (action === 'resetDevice') {
      row.dispositivo = '';
      row.token = '';
    } else if (action === 'resetPass') {
      const p = String(d.p || '').replace(/\D/g, '');
      if (p.length < 4 || p.length > 12) return fail_('erro', 'A senha deve ter de 4 a 12 números.');
      row.senhaHash = hash_(row.usuario, p);
      row.token = '';
    } else if (action === 'setAviso') {
      row.aviso = String(d.aviso || '').slice(0, 300);
    } else if (action === 'rename') {
      row.nome = String(d.nome || '').slice(0, 40);
    } else if (action === 'delete') {
      sheet_().deleteRow(row._row);
      return { ok: true };
    } else {
      return fail_('erro', 'Ação inválida.');
    }
    save_(row);
    return { ok: true };
  } finally {
    lock.releaseLock();
  }
}

/* ------------------------------------------------------------------ pagina do painel */

const PAGINA = `<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<base target="_top">
<title>Painel de acessos</title>
<style>
:root{--bg:#0a101e;--sf:#111a2e;--st:#1d2a47;--tx:#e8eef9;--mu:#8190ad;--cy:#2dd4f0;--gr:#22c55e;--am:#f59e0b;--rd:#ef4444}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--tx);font-family:Roboto,Arial,sans-serif;-webkit-text-size-adjust:100%}
.wrap{max-width:560px;margin:0 auto;padding:18px 16px 60px}
.center{min-height:90vh;display:flex;flex-direction:column;justify-content:center}
h1{font-size:26px;margin:0 0 4px;font-weight:700}
h1 b{color:var(--cy)}
h2{font-size:16px;margin:0 0 12px;font-weight:600}
.mu{color:var(--mu);font-size:13px}
.hide{display:none!important}
.top{display:flex;align-items:center;justify-content:space-between;margin-bottom:14px}
input,textarea{width:100%;font-family:inherit;background:#0c1322;border:1px solid var(--st);color:var(--tx);border-radius:12px;padding:12px;font-size:16px;margin:0 0 10px}
input:focus,textarea:focus{outline:none;border-color:var(--cy)}
textarea{resize:vertical;min-height:64px}
.g2{display:grid;grid-template-columns:1fr 1fr;gap:8px}
.g2 label{font-size:12px;color:var(--mu);display:block;margin:0 0 4px}
button{background:linear-gradient(90deg,var(--cy),#3b82f6);color:#04121a;border:0;border-radius:24px;padding:12px 18px;font-size:15px;font-weight:600}
button.ghost{background:#17213a;color:var(--tx);border:1px solid var(--st)}
button.sm{padding:8px 12px;font-size:13px;border-radius:16px}
button.red{color:var(--rd)}
button:active{transform:scale(.97)}
.card{background:var(--sf);border:1px solid var(--st);border-radius:20px;padding:16px;margin-bottom:12px}
.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin-bottom:12px}
.stat{background:var(--sf);border:1px solid var(--st);border-radius:16px;padding:10px 6px;text-align:center}
.stat b{display:block;font-size:22px}
.stat span{font-size:11px;color:var(--mu)}
.row{display:flex;gap:8px;align-items:center}
.row>*{flex:1}
.row>button{flex:0 0 auto}
.seg{display:flex;gap:8px;margin:0 0 10px}
.seg label{flex:1;background:#0c1322;border:1px solid var(--st);border-radius:12px;padding:10px;text-align:center;font-size:13px}
.seg input{display:none}
.seg label.on{border-color:var(--cy);color:var(--cy)}
.chips{display:flex;gap:6px;flex-wrap:wrap;margin:6px 0 10px}
.chip{background:#17213a;border:1px solid var(--st);color:var(--mu);border-radius:16px;padding:6px 12px;font-size:13px}
.chip.on{color:#04121a;background:var(--cy);border-color:var(--cy)}
.u{background:var(--sf);border:1px solid var(--st);border-radius:18px;padding:14px;margin-bottom:10px}
.u .h{display:flex;justify-content:space-between;align-items:center;gap:8px}
.u .n{font-size:18px;font-weight:700}
.badge{font-size:12px;border-radius:12px;padding:3px 10px;border:1px solid}
.b-ativo{color:var(--gr);border-color:var(--gr)}
.b-vencendo,.b-aguardando{color:var(--am);border-color:var(--am)}
.b-vencido,.b-bloqueado{color:var(--rd);border-color:var(--rd)}
.u p{margin:6px 0 0;font-size:13px;color:var(--mu)}
.acts{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}
pre{white-space:pre-wrap;background:#0c1322;border:1px solid var(--st);border-radius:12px;padding:12px;font-size:14px;margin:0 0 10px}
.err{color:var(--rd);font-size:13px;min-height:18px}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#1d2a47;color:var(--tx);padding:10px 16px;border-radius:14px;font-size:14px;opacity:0;pointer-events:none;transition:opacity .2s;z-index:20;max-width:90%}
#toast.show{opacity:1}
.modal{position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;z-index:30;padding:20px}
.modal .box{background:var(--sf);border:1px solid var(--st);border-radius:20px;padding:18px;width:100%;max-width:380px}
.modal h3{margin:0 0 6px}
.modal p{margin:0 0 12px;color:var(--mu);font-size:14px}
</style></head><body>
<div id="toast"></div>

<div id="modal" class="modal hide"><div class="box">
  <h3 id="mt"></h3><p id="mp"></p>
  <input id="mi" style="display:none">
  <div class="row"><button id="mc" class="ghost">Cancelar</button><button id="mo">OK</button></div>
</div></div>

<div id="login" class="wrap center">
  <h1>Painel de <b>acessos</b></h1>
  <p class="mu">Rota Card · área do administrador</p>
  <input id="key" type="password" placeholder="Senha do painel" autocomplete="off">
  <button id="enter">Entrar</button>
  <p id="lerr" class="err"></p>
</div>

<div id="app" class="wrap hide">
  <div class="top"><h1>Painel de <b>acessos</b></h1><button id="out" class="ghost sm">Sair</button></div>
  <div class="stats" id="stats"></div>

  <div class="card">
    <h2>Novo usuário</h2>
    <input id="nome" placeholder="Nome (opcional)" maxlength="40">
    <div class="row"><input id="nu" inputmode="numeric" placeholder="Usuário (só números)" maxlength="12"><button id="gu" class="ghost sm">Gerar</button></div>
    <div class="row"><input id="np" inputmode="numeric" placeholder="Senha (só números)" maxlength="12"><button id="gp" class="ghost sm">Gerar</button></div>
    <div class="row"><input id="nd" inputmode="numeric" value="30" placeholder="Dias liberados"><span class="mu" style="flex:2">dias de acesso</span></div>
    <div class="seg" id="seg">
      <label class="on" data-v="hoje"><input type="radio" name="c" value="hoje" checked>Contar a partir de hoje</label>
      <label data-v="primeiro"><input type="radio" name="c" value="primeiro">Contar a partir do 1º acesso</label>
    </div>
    <button id="create" style="width:100%">Criar usuário</button>
  </div>

  <div id="made" class="card hide"></div>

  <div class="card">
    <h2>Para todos os usuários</h2>
    <label class="mu" style="font-size:12px">Aviso que aparece no app de todos (deixe vazio para não mostrar)</label>
    <textarea id="cfgAviso" maxlength="300" placeholder="Ex.: Manutenção hoje às 22h."></textarea>
    <div class="g2">
      <div><label>Versão mínima (número do build)</label><input id="cfgMin" inputmode="numeric" placeholder="ex.: 120"></div>
      <div><label>Nota mínima padrão</label><input id="cfgRating" inputmode="decimal" placeholder="ex.: 4.8"></div>
      <div><label>Verde a partir de (R$/km)</label><input id="cfgGreen" inputmode="decimal" placeholder="ex.: 2.0"></div>
      <div><label>Amarelo a partir de (R$/km)</label><input id="cfgYellow" inputmode="decimal" placeholder="ex.: 1.5"></div>
      <div><label>R$/hora mínimo</label><input id="cfgHour" inputmode="decimal" placeholder="ex.: 40"></div>
      <div><label>Busca máxima (km)</label><input id="cfgPick" inputmode="decimal" placeholder="ex.: 3"></div>
    </div>
    <p class="mu" style="font-size:12px;margin:0 0 10px">Os valores padrão só valem para quem ainda não mudou essa configuração no app. Versão mínima: quem estiver abaixo é bloqueado até atualizar.</p>
    <button id="cfgSave" style="width:100%">Salvar para todos</button>
  </div>

  <div class="chips" id="chips"></div>
  <input id="q" placeholder="Buscar por usuário ou nome">
  <div id="list"></div>
</div>

<script>
var KEY='',USERS=[],NOW=0,TODAY='',FILTER='todos',CONTAR='hoje',LINK='__LINK__';
function $(i){return document.getElementById(i);}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e;}
function toast(m){var t=$('toast');t.textContent=m;t.className='show';setTimeout(function(){t.className='';},2800);}
function gen(){return String(Math.floor(100000+Math.random()*900000));}
function fdate(ms){var d=new Date(ms);return d.toLocaleDateString('pt-BR')+' '+d.toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'});}
function ago(ms){if(!ms)return 'nunca';var s=Math.floor((NOW-ms)/1000);if(s<90)return 'agora';var m=Math.floor(s/60);if(m<60)return 'há '+m+' min';var h=Math.floor(m/60);if(h<24)return 'há '+h+' h';return 'há '+Math.floor(h/24)+' dias';}

function api(action,data,cb,onFail){
  google.script.run
    .withSuccessHandler(function(r){
      if(!r||!r.ok){toast((r&&r.msg)||'Erro');if(r&&(r.code==='badkey'||r.code==='locked'||r.code==='nokey')){if(onFail)onFail(r);else logout();}return;}
      if(cb)cb(r);
    })
    .withFailureHandler(function(e){toast('Erro: '+((e&&e.message)||e));})
    .adminApi(KEY,action,data||{});
}

function ask(title,text,placeholder,numeric,okLabel,cb){
  $('mt').textContent=title;$('mp').textContent=text||'';
  var mi=$('mi');mi.style.display=placeholder?'block':'none';mi.value='';
  if(placeholder){mi.placeholder=placeholder;mi.setAttribute('inputmode',numeric?'numeric':'text');}
  $('mo').textContent=okLabel||'OK';
  $('modal').className='modal';
  $('mc').onclick=function(){$('modal').className='modal hide';};
  $('mo').onclick=function(){var v=mi.value;$('modal').className='modal hide';cb(v);};
}

function copy(text){
  try{
    if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(text).then(function(){toast('Copiado');},function(){fb(text);});return;}
  }catch(e){}
  fb(text);
}
function fb(text){
  var t=document.createElement('textarea');t.value=text;document.body.appendChild(t);t.select();
  try{document.execCommand('copy');toast('Copiado');}catch(e){toast('Não consegui copiar');}
  document.body.removeChild(t);
}

function logout(){
  KEY='';try{sessionStorage.removeItem('k');}catch(e){}
  $('app').className='wrap hide';$('login').className='wrap center';
}
function enter(){
  KEY=$('key').value;
  api('list',{},function(r){
    try{sessionStorage.setItem('k',KEY);}catch(e){}
    $('login').className='wrap center hide';$('app').className='wrap';
    render(r);loadCfg();
  },function(r){$('lerr').textContent=(r&&r.msg)||'Erro';});
}
function reload(){api('list',{},render);}
function loadCfg(){api('getConfig',{},function(r){var c=r.cfg||{},d=c.defaults||{};$('cfgAviso').value=c.aviso||'';$('cfgMin').value=c.minVer||'';$('cfgGreen').value=d.green||'';$('cfgYellow').value=d.yellow||'';$('cfgHour').value=d.minHour||'';$('cfgPick').value=d.maxPickup||'';$('cfgRating').value=d.minRating||'';});}
function saveCfg(){api('setConfig',{aviso:$('cfgAviso').value,minVer:$('cfgMin').value,defaults:{green:$('cfgGreen').value,yellow:$('cfgYellow').value,minHour:$('cfgHour').value,maxPickup:$('cfgPick').value,minRating:$('cfgRating').value}},function(){toast('Salvo para todos');});}

function counts(){
  var c={ativos:0,vencendo:0,vencidos:0,bloqueados:0};
  USERS.forEach(function(u){
    if(u.status==='bloqueado')c.bloqueados++;
    else if(u.status==='vencido')c.vencidos++;
    else{c.ativos++;if(u.status==='vencendo')c.vencendo++;}
  });
  return c;
}

function render(r){
  if(r){NOW=r.now;USERS=r.users;if(r.today)TODAY=r.today;}
  var c=counts(),st=$('stats');st.innerHTML='';
  [['Ativos',c.ativos,'var(--gr)'],['Vencendo',c.vencendo,'var(--am)'],['Vencidos',c.vencidos,'var(--rd)'],['Bloqueados',c.bloqueados,'var(--mu)']].forEach(function(x){
    var d=el('div','stat');var b=el('b',null,String(x[1]));b.style.color=x[2];d.appendChild(b);d.appendChild(el('span',null,x[0]));st.appendChild(d);
  });
  var ch=$('chips');ch.innerHTML='';
  [['todos','Todos'],['ativo','Ativos'],['vencendo','Vencendo'],['vencido','Vencidos'],['bloqueado','Bloqueados']].forEach(function(f){
    var b=el('button','chip'+(FILTER===f[0]?' on':''),f[1]);b.onclick=function(){FILTER=f[0];render();};ch.appendChild(b);
  });
  var q=$('q').value.toLowerCase().trim();
  var list=$('list');list.innerHTML='';
  var shown=USERS.filter(function(u){
    if(FILTER==='ativo'&&!(u.status==='ativo'||u.status==='aguardando'||u.status==='vencendo'))return false;
    if(FILTER!=='todos'&&FILTER!=='ativo'&&u.status!==FILTER)return false;
    if(q&&(u.usuario.indexOf(q)<0&&u.nome.toLowerCase().indexOf(q)<0))return false;
    return true;
  });
  if(!shown.length){list.appendChild(el('p','mu','Nenhum usuário aqui.'));return;}
  shown.forEach(function(u){list.appendChild(card(u));});
}

function card(u){
  var c=el('div','u');
  var h=el('div','h');
  var l=el('div');l.appendChild(el('div','n',u.usuario));if(u.nome)l.appendChild(el('div','mu',u.nome));
  var lab={ativo:'Ativo',vencendo:'Vencendo',aguardando:'Aguardando 1º acesso',vencido:'Vencido',bloqueado:'Bloqueado'}[u.status];
  h.appendChild(l);h.appendChild(el('span','badge b-'+u.status,lab));c.appendChild(h);
  var v;
  if(u.status==='aguardando')v='Libera '+u.dias+' dias a partir do 1º acesso';
  else if(u.status==='vencido')v='Venceu em '+fdate(u.expiraEm);
  else if(u.status==='bloqueado')v='Bloqueado'+(u.expiraEm?' · validade até '+fdate(u.expiraEm):'');
  else v='Vence em '+fdate(u.expiraEm)+' ('+u.diasRestantes+(u.diasRestantes===1?' dia)':' dias)');
  c.appendChild(el('p',null,v));
  c.appendChild(el('p',null,'Último acesso: '+ago(u.ultimoAcesso)+' · Aparelho: '+(u.aparelho?'vinculado ('+u.aparelho+')':'livre')));
  var uso=u.versao?('Versão '+u.versao+(u.diaStats===TODAY?' · hoje: '+u.ofertasHoje+' ofertas, '+u.aceitasHoje+' aceitas':' · sem uso hoje')):'Sem dados de uso ainda';
  c.appendChild(el('p',null,uso));
  if(u.aviso)c.appendChild(el('p',null,'Aviso: '+u.aviso));
  var a=el('div','acts');
  function B(t,fn,cls){var b=el('button','ghost sm'+(cls?' '+cls:''),t);b.onclick=fn;a.appendChild(b);}
  B('+7 dias',function(){api('addDays',{u:u.usuario,n:7},function(){toast('+7 dias');reload();});});
  B('+30 dias',function(){api('addDays',{u:u.usuario,n:30},function(){toast('+30 dias');reload();});});
  B('Dias…',function(){ask('Ajustar dias','Digite quantos dias somar (use - para tirar).','ex.: 15',false,'Aplicar',function(v){var n=parseInt(v,10);if(!n){toast('Número inválido');return;}api('addDays',{u:u.usuario,n:n},function(){toast('Atualizado');reload();});});});
  B('Recomeçar',function(){ask('Liberar a partir de hoje','Por quantos dias, contando de hoje?','ex.: 30',true,'Aplicar',function(v){var n=parseInt(v,10);if(isNaN(n)){toast('Número inválido');return;}api('setDays',{u:u.usuario,n:n},function(){toast('Atualizado');reload();});});});
  B(u.ativo?'Bloquear':'Desbloquear',function(){api('toggle',{u:u.usuario},function(){toast(u.ativo?'Bloqueado':'Desbloqueado');reload();});},u.ativo?'red':'');
  B('Liberar aparelho',function(){api('resetDevice',{u:u.usuario},function(){toast('Aparelho liberado');reload();});});
  B('Nova senha',function(){ask('Nova senha','Deixe vazio para gerar uma automática.','só números',true,'Trocar',function(v){var p=(v||'').replace(/\\D/g,'')||gen();api('resetPass',{u:u.usuario,p:p},function(){showMade(u.usuario,p,null,'Senha alterada');reload();});});});
  B('Aviso',function(){ask('Aviso para este usuário','Aparece no app dele. Deixe vazio para usar o aviso geral.','texto',false,'Salvar',function(v){api('setAviso',{u:u.usuario,aviso:v},function(){toast('Aviso salvo');reload();});});});
  B('Nome',function(){ask('Nome','Aparece no app do usuário.','nome',false,'Salvar',function(v){api('rename',{u:u.usuario,nome:v},function(){reload();});});});
  B('Excluir',function(){ask('Excluir usuário','Apagar '+u.usuario+' para sempre?',null,false,'Excluir',function(){api('delete',{u:u.usuario},function(){toast('Excluído');reload();});});},'red');
  c.appendChild(a);
  return c;
}

function showMade(u,p,dias,titulo){
  var m=$('made');m.className='card';m.innerHTML='';
  m.appendChild(el('h2',null,titulo||'Usuário criado'));
  var txt='*Rota Card* - acesso liberado\\nUsuário: '+u+'\\nSenha: '+p+(dias!==null?'\\nValidade: '+dias+' dias':'')+'\\nBaixar o app: '+LINK;
  var pre=el('pre',null,txt);m.appendChild(pre);
  var b=el('button',null,'Copiar mensagem');b.style.width='100%';b.onclick=function(){copy(txt);};m.appendChild(b);
  m.scrollIntoView();
}

function setContar(v){
  CONTAR=v;
  var ls=$('seg').getElementsByTagName('label');
  for(var i=0;i<ls.length;i++){ls[i].className=ls[i].getAttribute('data-v')===v?'on':'';}
}

window.onload=function(){
  $('enter').onclick=enter;
  $('key').onkeydown=function(e){if(e.keyCode===13)enter();};
  $('out').onclick=logout;
  $('cfgSave').onclick=saveCfg;
  $('gu').onclick=function(){$('nu').value=gen();};
  $('gp').onclick=function(){$('np').value=gen();};
  $('q').oninput=function(){render();};
  var ls=$('seg').getElementsByTagName('label');
  for(var i=0;i<ls.length;i++){(function(l){l.onclick=function(){setContar(l.getAttribute('data-v'));};})(ls[i]);}
  $('create').onclick=function(){
    var u=$('nu').value.replace(/\\D/g,''),p=$('np').value.replace(/\\D/g,''),dias=parseInt($('nd').value,10);
    if(u.length<4){toast('Usuário: mínimo 4 números');return;}
    if(p.length<4){toast('Senha: mínimo 4 números');return;}
    if(isNaN(dias)||dias<0){toast('Informe os dias');return;}
    api('create',{u:u,p:p,nome:$('nome').value,dias:dias,contar:CONTAR},function(r){
      showMade(r.u,r.p,r.dias);
      $('nu').value='';$('np').value='';$('nome').value='';
      reload();
    });
  };
  var k='';try{k=sessionStorage.getItem('k')||'';}catch(e){}
  if(k){$('key').value=k;enter();}
};
</script></body></html>`;
