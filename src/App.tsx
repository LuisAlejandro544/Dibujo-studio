/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import { getHelloWorldMessage } from './hello';

/**
 * Componente raíz de la versión web.
 * Tal como solicitó el usuario, la vista web muestra únicamente "Hello World"
 * para compilar sin fricción mientras la app móvil completa vive en Kotlin + Rust.
 */
export default function App() {
  const message = getHelloWorldMessage();

  return (
    <main className="min-h-screen w-full bg-slate-950 text-slate-100 flex items-center justify-center p-6">
      <h1 className="text-4xl font-semibold tracking-tight">{message}</h1>
    </main>
  );
}
