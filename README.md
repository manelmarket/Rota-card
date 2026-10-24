# Rota Card

App Android para motoristas de aplicativo. Lê a oferta de corrida na tela do **Uber**, da **99** e do **Easymob** e mostra por cima um cartão colorido com **R$/km**, **R$/h**, nota do passageiro, distância, o veredito (Aceitar, Pensar ou Recusar) e a comparação com a média do próprio motorista. Guarda as corridas aceitas com o print da oferta e gera relatórios em CSV e PDF.

## Como funciona

| Parte | Arquivo |
|---|---|
| Serviço de acessibilidade (lê a tela, mostra o cartão, detecta o aceite) | `CardService.kt` |
| Leitor de ofertas (regex sobre o texto da tela ou do OCR) | `Parser.kt` |
| Regras de aceite, recusa, "aceite provável" e arrasto do Easymob | `AcceptRules.kt` |
| Prints confirmados com a oferta na tela | `ShotStore.kt`, `ShotSaver.kt` |
| Cartão por cima dos apps | `CardOverlay.kt`, `CardUi.kt` |
| Média do motorista (últimos 30 dias) | `Baseline.kt` |
| Histórico, relatórios, backup | `Ride.kt`, `Report.kt`, `Backup.kt`, `HistoryActivity.kt` |
| Atualização automática pelas releases do GitHub | `Updater.kt` |
| Login e controle de acesso (opcional) | `License.kt`, `LoginActivity.kt`, `painel/Code.gs` |
| Apps de corrida suportados (nomes de pacote) | `Apps.kt` |
| Semáforos: cálculo do ciclo e previsão | `Semaforo.kt` |
| Semáforos: armazenamento, GPS, tela e mapa | `LightStore.kt`, `DriveService.kt`, `SemaforoActivity.kt`, `TileMapView.kt` |

- **Uber e Easymob** expõem o texto da oferta para a acessibilidade.
- **A 99** não expõe: o app tira um print e lê por OCR (ML Kit).
- **Aceite**: toque em "Aceitar"/"Selecionar", heurística de botão grande embaixo, arrasto para a direita no Easymob, ou "aceite provável" (a oferta some cedo, sem recusa). Os prováveis ficam marcados no histórico.

## Semáforos e navegação

Tela **Semáforos** (botão na tela principal).

**Cadastro:** parado num sinal, com o modo semáforos ligado, toque em **VERDE**, **AMARELO** e **VERMELHO** no instante em que cada cor acende, por 3 ciclos seguidos (uns 5 minutos). O app guarda a posição do GPS e a direção de quem chega (cada direção de um cruzamento é um sinal diferente) e dá ao sinal o nome da rua. Também dá para marcar pelos botões da notificação.

**Previsão:** com o modo ligado, o app acha o sinal cadastrado à frente (até 250 m, na mesma direção) e mostra "verde por mais 12 s" ou "vermelho, abre em 20 s":
- na tela;
- na notificação;
- **por cima de qualquer app** (Waze, Maps, Uber…), se a acessibilidade do Rota Card estiver ligada;
- em voz, entre 60 e 180 m do sinal.

**Como calcula** (`Semaforo.kt`): cada marcação é um ponto "volta n do ciclo, no instante t", e o app ajusta uma reta (mínimos quadrados) nesses pontos. Cada vez que você passa e marca o verde, o relógio do sinal se acerta e o ciclo fica mais preciso. O app estima o erro de cada previsão:
- **alta**: erro de até ~2 s;
- **média**: aparece com "~";
- **baixa**: não mostra número.

Mais detalhes do cálculo:
- **Plano por horário:** os tempos vêm de preferência das marcações feitas no mesmo horário do dia (±90 min), porque muitos sinais mudam no pico.
- **Sinal variável:** se os ciclos medidos variam mais de 10% (semáforo inteligente ou com botão), o app não prevê.
- **Testes:** um dia simulado com toques imprecisos, em 30 sorteios diferentes. Com 3 ciclos medidos, as previsões de confiança alta erraram no máximo 3 s. Em nenhum caso o app deu previsão confiável fora da margem que ele mesmo estimou.

**Limites:**
- se a prefeitura muda o tempo do sinal, a primeira passagem depois da mudança pode errar. Ao marcar, o app percebe e recomeça. Para limpar tudo, use **Medir de novo**;
- **sempre obedeça o semáforo**. A previsão é uma estimativa e o app nunca sugere acelerar.

**Navegação:** a rota é do Waze ou do Google Maps (botões na tela Semáforos). O Rota Card liga o modo semáforos e fica por cima mostrando o próximo sinal. O mapa da tela usa os blocos do OpenStreetMap, guardados no celular por 30 dias.

## Print das corridas aceitas

O print só é guardado quando há certeza de que a oferta está nele: no Uber e no Easymob, o texto da oferta ainda estava na tela quando a foto chegou; na 99, o OCR leu a oferta naquela imagem. O primeiro print sai assim que a oferta aparece. Na hora do aceite, o app usa esse print. Se a oferta já tinha saído da tela e não há print confirmado, a corrida entra no histórico sem print, em vez de salvar a foto de outra tela.

## Compilar

O GitHub Actions (`.github/workflows/build.yml`) compila tudo:

1. roda os testes (`gradle testDebugUnitTest`). **Se algum falhar, não sai versão**;
2. compila o APK (`gradle assembleDebug`);
3. calcula o SHA-256 do APK;
4. só em push na `main`, publica a release `build-N` com `RotaCard.apk` e `RotaCard.apk.sha256`. O app oferece a atualização e confere o SHA-256 antes de instalar.

Pull requests só compilam e testam (não publicam).

Localmente: `gradle testDebugUnitTest assembleDebug` (JDK 17, Android SDK 34).

## Assinatura

Hoje o APK é assinado com `app/debug.keystore`, que está no repositório, ou seja, é pública. Para usar uma chave própria:

1. Gere a chave (guarde o arquivo e as senhas em lugar seguro; sem eles não dá para publicar atualizações):
   ```
   keytool -genkeypair -v -keystore rotacard.jks -alias rotacard -keyalg RSA -keysize 4096 -validity 10000
   ```
2. No GitHub, em **Settings → Secrets and variables → Actions**, crie:
   - `ROTACARD_KEYSTORE_BASE64`: saída de `base64 -w0 rotacard.jks`
   - `ROTACARD_KEYSTORE_PASSWORD`, `ROTACARD_KEY_ALIAS` (ex.: `rotacard`), `ROTACARD_KEY_PASSWORD`

> **Atenção:** trocar a chave muda a assinatura do app. O Android **não instala por cima** de uma versão assinada com outra chave. Cada motorista vai precisar fazer **backup** (menu Backup), **desinstalar**, instalar a versão nova e **restaurar o backup**. Combine com os motoristas antes de criar os Secrets. Sem os Secrets, nada muda.

## Painel de acessos (opcional)

`painel/Code.gs` é um Google Apps Script ligado a uma Planilha Google. As instruções estão no topo do arquivo. Depois de implantar, cole a URL (termina em `/exec`) em `app/src/main/res/values/license.xml`. Enquanto estiver `COLE_AQUI_A_URL_DO_PAINEL`, o app funciona sem login.

O painel grava na aba **registro** cada ação do administrador (criar, bloquear, mudar dias etc.), sem senhas.

## Privacidade

- Prints das corridas aceitas: na galeria, álbum **RotaCard**.
- Prints das ofertas que o app não conseguiu ler: **só dentro do app**, apagados após 7 dias. Podem ser enviados (zip) em **Saúde do app → Enviar prints**.
- O backup automático do Google leva o histórico e as configurações. **Não** leva a licença (ela é presa ao aparelho) nem os prints.
- Com o login ativado, o app envia ao painel: usuário, código do aparelho, versão e quantas ofertas viu e aceitou no dia.
- Modo semáforos: a localização só é usada com o modo ligado (há uma notificação fixa enquanto isso) e os semáforos ficam só no celular (e no backup). O mapa baixa os blocos do OpenStreetMap da região que aparece na tela.
