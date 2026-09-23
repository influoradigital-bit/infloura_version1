import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import '@/app/globals.css'
import 'lenis/dist/lenis.css'
import { installCspViolationReporter } from '@/lib/csp-violation-reporter'

// F-1789 — once, before React renders, so CSP violations during boot are reported too
// (Report-Only in F-1787 stage 1, enforced in stage 2 — same event, same listener).
installCspViolationReporter()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
