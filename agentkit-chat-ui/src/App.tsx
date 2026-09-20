import { Admin } from './admin/Admin'
import { Console } from './components/Console'

/**
 * The page.
 *
 * Nothing but the console. The staleness check that used to live here moved into
 * {@link Console}, which was already fetching `/api/overview` — two components each fetching it
 * on mount is two requests where one will do, and the second one existed only because this file
 * did not have the answer the first one already had.
 */
export function App() {
  // The organization's admin view is its own page, at /admin; everything else is the console.
  if (globalThis.location?.pathname.startsWith('/admin')) {
    return <Admin />
  }
  return <Console />
}
