import { useEffect, useState } from 'react'
import { AiSecurity01Icon } from './AiSecurity01Icon'
import './App.css'
type Status = 'checking' | 'connected' | 'unavailable'
function App() {
  const [status, setStatus] = useState<Status>('checking')
  useEffect(() => {
    const controller = new AbortController()
    fetch('/api/v1/health', { signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error('Health check failed')
      const data: unknown = await response.json()
      if (typeof data !== 'object' || data === null || !('status' in data) || data.status !== 'UP') throw new Error('Unexpected response')
      setStatus('connected')
    }).catch(() => { if (!controller.signal.aborted) setStatus('unavailable') })
    return () => controller.abort()
  }, [])
  return <main className="shell">
    <header className="landing-nav"><a className="brand" href="/"><span className="mark"><AiSecurity01Icon size={18}/></span><span>DealShield <b>AI</b></span></a><nav><a href="#how-it-works">How it works</a><a href="#features">Platform</a><a href="/about">About</a><a className="phase" href="/login">SIGN IN ↗</a></nav></header>
    <section className="hero"><div className="hero-copy"><div className="eyebrow"><span /> PROCUREMENT DECISION SUPPORT</div><h1>Evidence first.<br/><em>Decisions clearer.</em></h1><p>Turn supplier quotations into a single, reviewable purchasing decision. Keep the evidence, compare the tradeoffs, and stay in control.</p><div className="hero-actions"><a className="hero-primary" href="/login">Start a comparison <span>→</span></a><a className="hero-secondary" href="#how-it-works">See how it works</a></div><div className="status" role="status" aria-live="polite"><span className={`dot ${status}`} /> API {status === 'checking' ? 'connection checking' : status === 'connected' ? 'connected' : 'unavailable'} <span className="status-detail">{status === 'unavailable' ? 'Start the backend on port 18180 and refresh.' : 'Secure buyer workspace'}</span></div></div><div className="product-visual" aria-hidden="true"><div className="orb"/><div className="visual-card visual-main"><small>LIVE COMPARISON</small><strong>Business laptops</strong><div className="visual-row"><span>TechZone</span><b>78.6</b></div><div className="visual-row active"><span>Apex Systems</span><b>71.2</b></div><div className="visual-meter"><i/></div><p>Evidence-led supplier ranking</p></div><div className="visual-card visual-mini"><small>RISK SIGNAL</small><b>Quantity mismatch</b><span>Needs review</span></div></div></section>
    <section className="steps" id="how-it-works" aria-label="How DealShield works"><article><span>01</span><h2>Upload offers</h2><p>Bring two or three private supplier quotations into one secure workspace.</p></article><article><span>02</span><h2>Review evidence</h2><p>Confirm extracted prices, specifications, terms, and missing information.</p></article><article><span>03</span><h2>Choose with clarity</h2><p>Compare the tradeoffs, risk signals, and buyer requirements before you decide.</p></article></section>
    <section className="feature-strip" id="features"><div><span>PRIVATE BY DESIGN</span><p>Buyer-scoped records and protected document storage.</p></div><div><span>PRODUCT AWARE</span><p>Compare quantity, unit price, terms, and documented specifications.</p></div><div><span>BUYER IN CONTROL</span><p>Recommendations support a decision; they do not make one for you.</p></div></section>
    <footer><span>DEALSHIELD AI</span><span>Decision support, with the buyer in control.</span></footer>
  </main>
}
export default App
