type MediaState = 'playing' | 'paused' | 'ended' | 'unknown';

const MESSAGE_TYPE = 'MEDIA_STATE';
let trackedVideo: HTMLVideoElement | null = null;
let observer: MutationObserver | null = null;
let hasPlayed = false;

function sendState(state: MediaState): void {
  void browser.runtime.sendMessage({ type: MESSAGE_TYPE, state }).catch(() => undefined);
}


function detachVideo(): void {
  if (!trackedVideo) return;
  trackedVideo.removeEventListener('play', handleMediaEvent);
  trackedVideo.removeEventListener('pause', handleMediaEvent);
  trackedVideo.removeEventListener('ended', handleMediaEvent);
  trackedVideo = null;
  hasPlayed = false;
}

function currentState(): MediaState {
  if (!trackedVideo) return 'unknown';
  if (trackedVideo.ended) return 'ended';
  if (!trackedVideo.paused) return 'playing';
  return hasPlayed ? 'paused' : 'unknown';
}

function emitCurrentState(): void {
  const state = currentState();
  if (state !== 'unknown') sendState(state);
}

function handleMediaEvent(event: Event): void {
  if (!trackedVideo) return;
  if (event.type === 'play') hasPlayed = true;
  emitCurrentState();
}

function attachCurrentVideo(): void {
  const nextVideo = document.querySelector('#movie_player video, video');
  if (!(nextVideo instanceof HTMLVideoElement)) {
    detachVideo();
    return;
  }
  if (nextVideo === trackedVideo) return;

  detachVideo();
  trackedVideo = nextVideo;
  trackedVideo.addEventListener('play', handleMediaEvent);
  trackedVideo.addEventListener('pause', handleMediaEvent);
  trackedVideo.addEventListener('ended', handleMediaEvent);
  if (!trackedVideo.paused && !trackedVideo.ended) hasPlayed = true;
  emitCurrentState();
}

browser.runtime.onMessage.addListener((message) => {
  if (message && typeof message === 'object' && (message as Record<string, unknown>).type === 'GET_MEDIA_STATE') {
    return currentState();
  }
  return undefined;
});

function start(): void {
  attachCurrentVideo();
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) sendState(currentState());
  });
  observer = new MutationObserver(attachCurrentVideo);
  const playerRoot = document.querySelector('#movie_player') ?? document.body ?? document.documentElement;
  if (playerRoot) observer.observe(playerRoot, { childList: true, subtree: true });
}

start();
