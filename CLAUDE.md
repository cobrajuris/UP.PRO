# Diretrizes de Projeto para o Claude Code

## Nero (`nero/`) — barra de vidro + assistente para Android
O usuário rejeitou a proposta antiga (pílula/orbe neon "Horizon Island"). O Nero segue o estilo da Apple
(iOS: vidro fosco, cantos bem arredondados, animações elásticas) e foi definido junto com o usuário.

### UX
- **Base da tela:** a barra de atalhos de vidro fica sempre visível, logo acima da barra de gestos. Nunca no topo (câmera).
- **Atalhos da barra:** pílula "Nero" (assistente), Notas, Lembretes/Agenda, Notebook, Alarme, Álbum favorito.
- **Cartão de atividade** acima da barra, só quando há algo acontecendo, tingido pela cor do contexto
  (música = cor/capa do álbum desfocada, como um mini-player; Maps/Waze, timer, compromisso em 15 min, carregando, notebook).
- **Gestos:** toque nos atalhos; deslizar a barra para cima abre o painel (estilo Control Center); deslizar para baixo fecha.
- **Tela cheia/paisagem:** tudo encolhe para um tracinho de vidro fino; um toque mostra a barra por alguns segundos.
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
- `overlay/OverlayService` + `OverlayWindow` (Dialogs de sobreposição para barra, cartão, painel e tracinho).
- `ui/IslandViews` (composables da ilha), `ui/MainActivity` (app: Início, Notas, Lembretes, Notebook, Ajustes).
- `media/NeroListenerService` lê MediaSessions e notificações de navegação.

### Testes rápidos
- O `CommandParser` é Kotlin puro: dá para compilar e testar com o `kotlinc` fora do Android.

## Build
- O APK é compilado pelo workflow `.github/workflows/nero-apk.yml` e publicado no release `nero-latest`
  (este ambiente não acessa o Google Maven).
- Local: `cd nero && ./gradlew assembleRelease` (requer Android SDK 35).
