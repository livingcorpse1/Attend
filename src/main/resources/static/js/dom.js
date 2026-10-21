/**
 * A very small hyperscript helper.
 *
 * The application never builds markup with string concatenation. Every node is created
 * through `h()` so text coming from the server is inserted as a text node and can never
 * be interpreted as markup.
 *
 * @param {string} spec Tag with optional `.class` and `#id` shortcuts, e.g. `button.primary`.
 * @param {object|null} [props] Properties, attributes and event handlers.
 * @param {...(Node|string|number|false|null|undefined|Array)} children
 * @returns {HTMLElement}
 */
export function h(spec, props, ...children) {
  const { tag, classes, id } = parseSpec(spec);
  const element = document.createElement(tag);

  if (id) {
    element.id = id;
  }
  if (classes.length > 0) {
    element.className = classes.join(' ');
  }

  if (props && typeof props === 'object' && !isRenderable(props)) {
    applyProps(element, props);
  } else if (props !== undefined && props !== null) {
    children.unshift(props);
  }

  appendChildren(element, children);
  return element;
}

/**
 * Creates an SVG element. SVG needs its own namespace, which `h()` cannot infer.
 */
export function svg(tag, props = {}, ...children) {
  const element = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) {
      continue;
    }
    if (key.startsWith('on') && typeof value === 'function') {
      element.addEventListener(key.slice(2).toLowerCase(), value);
    } else {
      element.setAttribute(key, String(value));
    }
  }
  appendChildren(element, children);
  return element;
}

/**
 * Replaces the children of a node.
 */
export function replaceChildren(node, ...children) {
  node.replaceChildren();
  appendChildren(node, children);
  return node;
}

/**
 * Reads a value from a form as a plain object.
 */
export function formValues(form) {
  const values = {};
  for (const element of form.elements) {
    if (!element.name || element.disabled) {
      continue;
    }
    if (element.type === 'checkbox') {
      values[element.name] = element.checked;
    } else if (element.type === 'radio') {
      if (element.checked) {
        values[element.name] = element.value;
      }
    } else {
      values[element.name] = element.value;
    }
  }
  return values;
}

function parseSpec(spec) {
  const parts = String(spec).split(/(?=[.#])/);
  const tag = parts[0] || 'div';
  const classes = [];
  let id = '';
  for (const part of parts.slice(1)) {
    if (part.startsWith('.')) {
      classes.push(part.slice(1));
    } else if (part.startsWith('#')) {
      id = part.slice(1);
    }
  }
  return { tag, classes, id };
}

function applyProps(element, props) {
  for (const [key, value] of Object.entries(props)) {
    if (value === null || value === undefined || value === false) {
      continue;
    }
    if (key === 'class' || key === 'className') {
      const extra = String(value).split(/\s+/).filter(Boolean);
      if (extra.length > 0) {
        element.className = [element.className, ...extra].filter(Boolean).join(' ');
      }
    } else if (key === 'style' && typeof value === 'object') {
      Object.assign(element.style, value);
    } else if (key === 'dataset' && typeof value === 'object') {
      Object.assign(element.dataset, value);
    } else if (key === 'html') {
      element.innerHTML = value;
    } else if (key.startsWith('on') && typeof value === 'function') {
      element.addEventListener(key.slice(2).toLowerCase(), value);
    } else if (key === 'value') {
      element.value = value;
    } else if (key === 'checked' || key === 'disabled' || key === 'selected' || key === 'readOnly') {
      element[key] = Boolean(value);
    } else if (value === true) {
      element.setAttribute(key, '');
    } else {
      element.setAttribute(key, String(value));
    }
  }
}

function appendChildren(element, children) {
  for (const child of children.flat(Infinity)) {
    if (child === null || child === undefined || child === false || child === true) {
      continue;
    }
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

function isRenderable(value) {
  return value instanceof Node || Array.isArray(value);
}
