/**
 * The live channel.
 *
 * <p>The server pushes a note when something changes, and the screens read the data
 * they need again. The teacher therefore sees a student arrive at once, and the student
 * sees their own attendance without touching anything.
 *
 * <p>The socket is opened once and kept open. If it breaks, it is opened again, because
 * a screen that lost the connection may still be the one that is on the page.
 */

const listeners = new Map();
let socket = null;
let reconnectTimer = null;

/**
 * Tells the server which rooms the client watches.
 *
 * @param {number} id The identifier of the account.
 * @returns {Function} A function that stops the watching.
 */
export function watchAccount(id) {
  const room = String(id);
  const handler = (event) => {
    let message;
    try {
      message = JSON.parse(event.data);
    } catch (error) {
      return;
    }
    if (message.room !== room) {
      return;
    }
    for (const callback of listeners.get(message.topic) || []) {
      try {
        callback(message);
      } catch (error) {
        // A screen that fails to redraw must not take the channel down.
      }
    }
  };
  addEventListener('veriqra:live', handler);
  connect();
  return () => {
    removeEventListener('veriqra:live', handler);
  };
}

/**
 * Registers a callback for one kind of change.
 *
 * @param {string} topic For example `roster` or `sessions`.
 * @param {Function} callback Called when the topic changes.
 * @returns {Function} A function that stops the listening.
 */
export function onChange(topic, callback) {
  if (!listeners.has(topic)) {
    listeners.set(topic, new Set());
  }
  listeners.get(topic).add(callback);
  return () => {
    listeners.get(topic)?.delete(callback);
  };
}

/**
 * The state of the live channel, for the checks and for the diagnostics.
 *
 * @returns {{open: boolean, rooms: number}}
 */
export function state() {
  return {
    open: Boolean(socket && socket.readyState === WebSocket.OPEN),
    rooms: listeners.size
  };
}

/**
 * Opens the socket, if it is not open already.
 */
function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }
  const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  try {
    socket = new WebSocket(`${protocol}//${location.host}/api/live`);
  } catch (error) {
    scheduleReconnect();
    return;
  }
  socket.addEventListener('message', (event) => {
    dispatchEvent(new CustomEvent('veriqra:live', { detail: event }));
  });
  socket.addEventListener('close', () => {
    socket = null;
    scheduleReconnect();
  });
  socket.addEventListener('error', () => {
    try {
      socket?.close();
    } catch (error) {
      // The close is already on its way.
    }
  });
}

/**
 * Opens the socket again after a moment, so a broken connection heals itself.
 */
function scheduleReconnect() {
  if (reconnectTimer) {
    return;
  }
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, 5000);
}
