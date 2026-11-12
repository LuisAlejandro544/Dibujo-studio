/**
 * Módulo de saludo mínimo para el entorno web.
 * Su única función es proveer el mensaje "Hello World" solicitado para la vista web,
 * manteniendo la lógica desacoplada para poder validarla con pruebas unitarias.
 */
export function getHelloWorldMessage(): string {
  return 'Hello World';
}
