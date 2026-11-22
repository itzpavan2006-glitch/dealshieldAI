import { useEffect, useState, type FormEvent } from 'react'
import { BrowserRouter, Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import Landing from './App'
import { ComparisonDetail, ComparisonHistory, CreateComparison } from './ComparisonPages'
import { BuyerPreferencesPage, UploadQuotationsPage } from './ProcurementPages'
import { AiSecurity01Icon } from './AiSecurity01Icon'
import './authentication.css'

type Buyer = { buyerId: string; displayName: string; comparisonCount: number; quotationCount: number }
type ApiState = 'checking' | 'connected' | 'unavailable'
async function csrf(): Promise<{ headerName: string; token: string }> {
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin', cache: 'no-store' })
  if (!response.ok) throw new Error('Could not start a secure session. Please try again.')
  return response.json() as Promise<{ headerName: string; token: string }>
}
async function post(path: string, body?: object): Promise<Response> {
  const token = await csrf()
  return fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', [token.headerName]: token.token }, body: body ? JSON.stringify(body) : undefined })
}
function Brand() { return <a className="auth-brand" href="/"><span><AiSecurity01Icon size={20}/></span>DealShield <b>AI</b></a> }
function ApiStatus() {
  const [state,setState]=useState<ApiState>('checking')
  useEffect(()=>{ const controller=new AbortController(); fetch('/api/v1/health',{cache:'no-store',signal:controller.signal}).then(async response=>{ if(!response.ok)throw new Error(); const body=await response.json() as {status?:string}; if(body.status!=='UP')throw new Error(); setState('connected') }).catch(()=>{if(!controller.signal.aborted)setState('unavailable')}); return ()=>controller.abort() },[])
  return <p className={`api-state ${state}`} role="status">Backend API {state==='checking' ? 'checking...' : state}</p>
}
function Login() {
  const [email,setEmail] = useState(''); const [password,setPassword] = useState('')
  const [mode,setMode] = useState<'login'|'register'>('login'); const [notice,setNotice] = useState('')
  const [busy,setBusy] = useState(false); const [error,setError] = useState(''); const navigate=useNavigate(); const location=useLocation()
  const sessionExpired=new URLSearchParams(location.search).get('reason')==='session-expired'
  const emailActionFailed=new URLSearchParams(location.search).get('error')==='email-action'
  async function submitCredentials(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError(''); setNotice('')
    try {
      const response=await post(mode==='register' ? '/api/v1/auth/register' : '/api/v1/auth/login',{email,password}); setPassword('')
      if (!response.ok) throw new Error(response.status===503 ? 'Authentication is not configured yet.' : mode==='register' ? 'Unable to create your account. Check the details and try again.' : 'Unable to sign in. Check your email and password, or use Forgot password to set or reset an email password.')
      if (response.status===202) { setMode('login'); setNotice('Check your email to confirm your account, then sign in here.'); return }
      navigate('/dashboard')
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not complete authentication.') } finally { setBusy(false) }
  }
  async function google() {
    setBusy(true); setError(''); setNotice('')
    try {
      const response=await post('/api/v1/auth/google')
      if (!response.ok) throw new Error('Google sign-in is not available yet.')
      const data=await response.json() as { url: string }; const url=new URL(data.url)
      if (url.protocol!=='https:') throw new Error('Unable to start Google sign-in.')
      window.location.assign(url.toString())
    } catch (err) { setError(err instanceof Error ? err.message : 'Could not start Google sign-in.'); setBusy(false) }
  }
  const registering=mode==='register'
  return <main className="auth-page"><header><Brand/><a href="/">Back to home</a></header><section className="auth-card"><div className="auth-kicker">BUYER WORKSPACE</div><h1>{registering ? 'Create your account.' : 'Welcome back.'}</h1><p>{registering ? 'Register as a buyer with your email address.' : 'Sign in to access your procurement workspace.'}</p>{sessionExpired&&<p className="auth-error" role="alert">Your secure session expired. Sign in again to continue.</p>}{emailActionFailed&&<p className="auth-error" role="alert">That email link is invalid or has expired. Request a new one below.</p>}<button type="button" className="google-button" onClick={google} disabled={busy}>Continue with Google</button><div className="divider"><span>OR CONTINUE WITH EMAIL</span></div><form onSubmit={submitCredentials}><label htmlFor="email">Email address</label><input id="email" type="email" autoComplete="email" value={email} onChange={event=>setEmail(event.target.value)} required/><label htmlFor="password">Password</label><input id="password" type="password" minLength={8} autoComplete={registering ? 'new-password' : 'current-password'} value={password} onChange={event=>setPassword(event.target.value)} required/>{notice && <p role="status">{notice}</p>}{error && <p className="auth-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy ? 'Please wait...' : registering ? 'Create account' : 'Sign in'}</button></form>{!registering&&<Link className="link-button auth-mode-toggle" to="/forgot-password">Forgot password or need to set one?</Link>}<button type="button" className="link-button auth-mode-toggle" disabled={busy} onClick={()=>{setMode(registering ? 'login' : 'register');setError('');setNotice('');setPassword('')}}>{registering ? 'Already have an account? Sign in' : 'New buyer? Create an account'}</button><ApiStatus/><p className="auth-foot">Access is limited to authenticated buyers.</p></section></main>
}
function PasswordRecovery(){
  const [email,setEmail]=useState(''); const [busy,setBusy]=useState(false); const [notice,setNotice]=useState(''); const [error,setError]=useState('')
  async function submit(event:FormEvent){event.preventDefault();setBusy(true);setError('');try{const response=await post('/api/v1/auth/password-recovery',{email});if(!response.ok)throw new Error('Unable to request a reset email. Please try again.');setNotice('If an eligible account exists, a secure email has been sent. Check your inbox and spam folder.')}catch(err){setError(err instanceof Error?err.message:'Unable to request a reset email.')}finally{setBusy(false)}}
  return <main className="auth-page"><header><Brand/><Link to="/login">Back to sign in</Link></header><section className="auth-card"><div className="auth-kicker">ACCOUNT RECOVERY</div><h1>Set or reset your password.</h1><p>Use this for an email/password account or to add a password to an account first created with Google.</p><form onSubmit={submit}><label htmlFor="recovery-email">Email address</label><input id="recovery-email" type="email" autoComplete="email" value={email} onChange={event=>setEmail(event.target.value)} required/>{notice&&<p role="status">{notice}</p>}{error&&<p className="auth-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy?'Please wait...':'Send secure email'}</button></form></section></main>
}
function ResetPassword(){
  const [password,setPassword]=useState(''); const [confirm,setConfirm]=useState(''); const [busy,setBusy]=useState(false); const [error,setError]=useState(''); const navigate=useNavigate()
  async function submit(event:FormEvent){event.preventDefault();setError('');if(password!==confirm){setError('Passwords do not match.');return}setBusy(true);try{const response=await post('/api/v1/auth/password',{password});if(response.status===401)throw new Error('This secure reset session has expired. Request a new email.');if(!response.ok)throw new Error('Password update could not be completed. Please request a new email.');setPassword('');setConfirm('');navigate('/dashboard',{replace:true})}catch(err){setError(err instanceof Error?err.message:'Password update could not be completed.')}finally{setBusy(false)}}
  return <main className="auth-page"><header><Brand/><Link to="/login">Back to sign in</Link></header><section className="auth-card"><div className="auth-kicker">ACCOUNT RECOVERY</div><h1>Choose a new password.</h1><p>This page is available only after a verified recovery email link.</p><form onSubmit={submit}><label htmlFor="new-password">New password</label><input id="new-password" type="password" minLength={8} autoComplete="new-password" value={password} onChange={event=>setPassword(event.target.value)} required/><label htmlFor="confirm-password">Confirm new password</label><input id="confirm-password" type="password" minLength={8} autoComplete="new-password" value={confirm} onChange={event=>setConfirm(event.target.value)} required/>{error&&<p className="auth-error" role="alert">{error}</p>}<button className="primary-button" disabled={busy}>{busy?'Saving...':'Save password'}</button></form></section></main>
}
function About(){return <main className="about-page"><header><Brand/><nav><Link to="/">Home</Link><Link to="/login">Sign in</Link></nav></header><section className="about-hero"><p className="auth-kicker">ABOUT DEALSHIELD AI</p><h1>Procurement decisions deserve a clearer trail.</h1><p>DealShield AI helps buyers turn supplier quotations into an evidence-led comparison while preserving the buyer’s final authority.</p><Link className="primary-button about-cta" to="/login">Enter your workspace</Link></section><section className="about-grid"><article><span>01</span><h2>Bring in offers</h2><p>Upload private quotations or enter them manually when a source document is unavailable.</p></article><article><span>02</span><h2>Review evidence</h2><p>Confirm extracted details, commercial terms, technical specifications, and gaps.</p></article><article><span>03</span><h2>Compare with context</h2><p>Assess product fit, quantity-normalized pricing, terms, and documented risks.</p></article></section><section className="about-note"><div><p className="auth-kicker">SECURITY & PRIVACY</p><h2>Private buyer workspaces, not shared supplier data.</h2></div><p>Server-managed sessions, CSRF protection, buyer-scoped database access, and private document storage protect the workflow. DealShield explains available evidence; it does not replace commercial judgement or legal review.</p></section></main>}
function Dashboard() {
  const [buyer,setBuyer]=useState<Buyer | null>(null); const [error,setError]=useState(''); const [busy,setBusy]=useState(true); const navigate=useNavigate()
  useEffect(()=>{ const controller=new AbortController(); fetch('/api/v1/buyer/dashboard',{credentials:'same-origin',cache:'no-store',signal:controller.signal}).then(async response=>{ if(response.status===401){navigate('/login',{replace:true});return} if(!response.ok)throw new Error('Could not load your workspace.'); setBuyer(await response.json() as Buyer) }).catch(err=>{if(!controller.signal.aborted)setError(err instanceof Error?err.message:'Could not load your workspace.')}).finally(()=>{if(!controller.signal.aborted)setBusy(false)}); return ()=>controller.abort() },[navigate])
  async function logout(){ try {const response=await post('/api/v1/auth/logout'); if(!response.ok)throw new Error('Could not sign out. Please try again.'); navigate('/login',{replace:true})} catch(err){setError(err instanceof Error?err.message:'Could not sign out.')} }
  return <main className="auth-page"><header><Brand/><div className="dashboard-actions"><Link to="/comparisons">Comparison history</Link><Link to="/quotations/upload">New comparison</Link><Link to="/about">About</Link><button className="link-button" onClick={logout}>Sign out</button></div></header><section className="workspace"><div className="auth-kicker">BUYER WORKSPACE</div><h1>{buyer ? `Welcome, ${buyer.displayName}.` : 'Your workspace.'}</h1><p>Review your purchasing decisions with clarity and confidence.</p>{busy && <p role="status">Checking your session...</p>}{error && <p className="auth-error" role="alert">{error}</p>}{buyer && <><div className="dashboard-metrics"><article><strong>{buyer.comparisonCount}</strong><span>Comparisons</span></article><article><strong>{buyer.quotationCount}</strong><span>Quotations</span></article></div><div className="empty-workspace"><span className="workspace-icon">&#10003;</span><h2>Secure procurement workspace</h2><p>Upload supplier quotations, review extracted facts, confirm optional preferences, and analyze the offers.</p><Link className="dashboard-cta" to="/quotations/upload">Start a comparison</Link></div></>}</section></main>
}
export default function AuthenticationApp(){ return <BrowserRouter><Routes><Route path="/" element={<Landing/>}/><Route path="/about" element={<About/>}/><Route path="/login" element={<Login/>}/><Route path="/forgot-password" element={<PasswordRecovery/>}/><Route path="/reset-password" element={<ResetPassword/>}/><Route path="/dashboard" element={<Dashboard/>}/><Route path="/requirements/new" element={<Navigate to="/quotations/upload" replace/>}/><Route path="/quotations/upload" element={<UploadQuotationsPage/>}/><Route path="/buyer-preferences" element={<BuyerPreferencesPage/>}/><Route path="/comparisons" element={<ComparisonHistory/>}/><Route path="/comparisons/new" element={<CreateComparison/>}/><Route path="/comparisons/:id" element={<ComparisonDetail/>}/><Route path="*" element={<Navigate to="/" replace/>}/></Routes></BrowserRouter> }
