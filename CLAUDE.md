# Diretrizes de Projeto para o Claude Code

## Padrões de UI/UX para Notificações Dinâmicas (Horizon Island — `dynamic-island/`)
- **Zonas seguras:** a ilha fica na base da tela (zona do polegar) ou encaixada nas laterais. Nunca fixar elementos no centro do topo (furo de câmera varia entre Samsung, Motorola, Xiaomi). Recortes de câmera (`displayCutout`) devem ser evitados no posicionamento.
- **Por demanda:** só aparece com algo ativo (mídia tocando, cronômetro, alerta). Sem atividade, some por completo.
- **Sensível a mídia:** em tela cheia (vídeo/jogo) ou paisagem, encolhe para uma aba lateral translúcida.
- **Não-intrusão:** não cobrir legendas de vídeos nem controles principais de jogos.
- **Animações:** transições por mola no Jetpack Compose (`spring`, `AnimatedContent` + `SizeTransform`, `animateDpAsState`, `updateTransition`); nada de saltos bruscos.
- **Feedback tátil:** toda interação de toque/arrasto usa `HapticFeedbackConstants`.

## Estética "Project Horizon"
- Vidro fumê translúcido (sem caixas pretas opacas), reflexo no topo, borda neon de 1dp em gradiente que se move.
- Acento por contexto: ciano/violeta = música, âmbar = cronômetro, brasa = alerta.
- Brilho ambiente que "respira" lentamente; números e timers em fonte monoespaçada.
- Textos em branco e cinza futurista `#A0A0AB`; ícones Material (rounded).

## Arquitetura
- `state/IslandRepository` — estado global (StateFlow) compartilhado por app, overlay e leitor de mídia.
- `media/MediaListenerService` — `NotificationListenerService` que lê as MediaSessions ativas.
- `overlay/IslandOverlayService` — serviço em primeiro plano que desenha a ilha via `SYSTEM_ALERT_WINDOW`, cuida de arrasto/encaixe, tela cheia e paisagem.
- `ui/NeoHorizonPill` — componente visual puro (sem lógica de dados).

## Build
- O APK é compilado pelo workflow `.github/workflows/horizon-island-apk.yml` e publicado no release `horizon-island-latest`.
- Local: `cd dynamic-island && ./gradlew assembleRelease` (requer Android SDK 35).
