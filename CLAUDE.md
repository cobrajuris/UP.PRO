# Diretrizes de Projeto para o Claude Code

## Nero (`nero/`) — barra de vidro + assistente para Android
O usuário rejeitou a proposta antiga (pílula/orbe neon "Horizon Island"). O Nero segue o estilo da Apple
(iOS: vidro fosco, cantos bem arredondados, animações elásticas) e foi definido junto com o usuário.

### UX
- **Pílula em miniatura no canto de baixo** (esquerdo por padrão; arrastável para cima e para o outro canto, encaixa sozinha).
  Nunca no topo (câmera). A barra larga antiga foi rejeitada pelo usuário: "bem pequena, formato de pílula".
- **Recolhida:** pílula escura pequena com o ícone do Nero.
- **Com atividade:** cresce, mostra o número grande no meio ("20 segundos", "78 %", "15 min", capa + ondas da música)
  e o **contorno vira barra de progresso** (cor = o que falta, cinza = o que já passou), como na referência do usuário.
- **Gestos:** toque abre o painel (estilo Control Center, com a atividade atual e os atalhos: Nero, Notas, Lembretes,
  Notebook, Alarme, Álbum favorito); segurar abre o assistente; arrastar move.
- **Tela cheia/paisagem:** vira um tracinho no canto; um toque mostra a pílula por alguns segundos.
- **Assistente offline (sem IA):** voz (SpeechRecognizer pt-BR) ou texto; `assistant/CommandParser` entende lembretes, notas, timer e alarme.
- **Lembretes:** salvos no app (AlarmManager) e no Google Agenda via CalendarContract (sem login extra).
- **Notebook:** servidor HTTP local (`notebook/NotebookServer`, sem bibliotecas) + página `assets/notebook.html`, protegido por PIN.
  Celular → notebook (botão na barra e "Compartilhar") e notebook → celular (Downloads/Nero).
- **Feedback tátil** em todo toque/arrasto (`HapticFeedbackConstants`).

### Visual
- Vidro fosco real no Android 12+ (`setBackgroundBlurRadius` nas janelas da ilha); antes disso, vidro escuro translúcido.
- Textos em branco e cinza `#A0A0AB`; ícones Material Rounded; fonte do sistema com números tabulares.

### Arquitetura
- `data/Store` (notas, lembretes, ajustes persistidos) e `data/NeroState` (estado ao vivo em StateFlow).
- `overlay/OverlayService` + `OverlayWindow` (Dialogs de sobreposição para a pílula e o painel) + `DragFrameLayout` (arrasto).
- `ui/IslandViews` (`MiniPill` com `pillOutline`, painel e cartões), `ui/MainActivity` (app: Início, Notas, Lembretes, Notebook, Ajustes).
- `media/NeroListenerService` lê MediaSessions e notificações de navegação.

### Testes rápidos
- O `CommandParser` é Kotlin puro: dá para compilar e testar com o `kotlinc` fora do Android.

## Build
- O APK é compilado pelo workflow `.github/workflows/nero-apk.yml` e publicado no release `nero-latest`
  (este ambiente não acessa o Google Maven).
- Local: `cd nero && ./gradlew assembleRelease` (requer Android SDK 35).
