import test from 'node:test';
import assert from 'node:assert/strict';
import { getHelloWorldMessage } from './hello';

/**
 * Pruebas unitarias rápidas para verificar el mensaje Hello World de la interfaz web.
 */
test('retorna exactamente Hello World', () => {
  assert.equal(getHelloWorldMessage(), 'Hello World');
});
