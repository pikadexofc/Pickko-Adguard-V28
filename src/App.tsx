import React, { useState, useEffect, memo } from 'react';
import {
  Shield,
  ShieldCheck,
  ShieldAlert,
  Settings,
  Wrench,
  ChevronRight,
  Zap,
  Globe,
  Pause,
  CheckCircle2,
  Lock,
  User,
  X,
  Gauge,
  Wifi,
  BarChart3,
  Crown,
  Info,
  Battery,
  Activity,
  History
} from 'lucide-react';
import { registerPlugin } from '@capacitor/core';
import Logo from './components/Logo';

const VpnPlugin = registerPlugin<any>('VpnPlugin');

// --- PREMIUM DESIGN SYSTEM ---
const styles = `
  @keyframes fade-in { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }
  @keyframes spring-up { from { transform: translateY(100%); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
  @keyframes scale-in { from { transform: scale(0.95); opacity: 0; } to { transform: scale(1); opacity: 1; } }
  @keyframes pulse-ring { 
    0% { transform: scale(0.8); opacity: 0.5; }
    100% { transform: scale(1.4); opacity: 0; }
  }
  @keyframes flow {
    0% { background-position: 0% 50%; }
    50% { background-position: 100% 50%; }
    100% { background-position: 0% 50%; }
  }
  
  .animate-fade-in { animation: fade-in 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
  .animate-spring-up { animation: spring-up 0.7s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
  .animate-scale-in { animation: scale-in 0.5s cubic-bezier(0.16, 1, 0.3, 1) forwards; }
  .pulse-active { position: relative; }
  .pulse-active::after {
    content: '';
    position: absolute;
    inset: 0;
    border-radius: 9999px;
    background: #CEFF00;
    animation: pulse-ring 2s cubic-bezier(0.215, 0.61, 0.355, 1) infinite;
    z-index: -1;
  }
  
  .scrollbar-hide::-webkit-scrollbar { display: none; }
  .scrollbar-hide { -ms-overflow-style: none; scrollbar-width: none; }
  
  .glass-panel { 
    background: rgba(28, 30, 26, 0.7); 
    backdrop-filter: blur(32px); 
    border: 1px solid rgba(255, 255, 255, 0.05); 
    box-shadow: 0 8px 32px 0 rgba(0, 0, 0, 0.4);
  }
  .glass-card { 
    background: rgba(255, 255, 255, 0.03); 
    border: 1px solid rgba(255, 255, 255, 0.08); 
  }
  .accent-gradient {
    background: linear-gradient(-45deg, #CEFF00, #9EFF00, #CEFF00, #8EFF00);
    background-size: 400% 400%;
    animation: flow 15s ease infinite;
  }
`;

export default function App() {
  const [currentScreen, setCurrentScreen] = useState('splash');
  const [activeTab, setActiveTab] = useState('home');
  const [activeSheet, setActiveSheet] = useState<string | null>(null);

  const [isProtected, setIsProtected] = useState(false);
  const [currentMode, setCurrentMode] = useState('Balanced');
  const [currentProvider, setCurrentProvider] = useState('AdGuard DNS');
  const [isTestingSpeed, setIsTestingSpeed] = useState(false);
  const [isPremium, setIsPremium] = useState(false);
  const [stats, setStats] = useState({ totalQueries: '0', adsBlocked: '0', trackers: '0', dataSaved: '0 MB' });
  const [recentLogs, setRecentLogs] = useState<string[]>([]);

  // Initialize and check status
  useEffect(() => {
    const init = async () => {
      try {
        const status = await VpnPlugin.getStatus();
        setIsProtected(status.isProtected);
        if (status.currentProvider) setCurrentProvider(status.currentProvider);
        if (status.currentMode) setCurrentMode(status.currentMode);
        if (status.stats) setStats(status.stats);
        if (status.recentActivity) setRecentLogs(status.recentActivity);
        setIsPremium(!!status.isPremium);
        if (status.onboardingComplete) setCurrentScreen('main');
      } catch (e) { console.warn("Init failed", e); }
    };
    init();
  }, []);

  // Sync activity and stats
  useEffect(() => {
    let interval: any;
    if (isProtected && currentScreen === 'main') {
      interval = setInterval(async () => {
        try {
          const { stats: s, recentActivity: l } = await VpnPlugin.getStatus();
          if (s) setStats(s);
          if (l) setRecentLogs(l);
        } catch (e) {}
      }, 3000);
    }
    return () => clearInterval(interval);
  }, [isProtected, currentScreen]);

  const toggleProtection = async (enable: boolean) => {
    try {
      if (enable) await VpnPlugin.enableShield({ provider: currentProvider, mode: currentMode });
      else await VpnPlugin.disableShield();
      setIsProtected(enable);
    } catch (e) { 
      setIsProtected(enable); // Fallback UI state
    }
  };

  const updateConfig = async (provider: string, mode: string) => {
    try { await VpnPlugin.updateConfig({ provider, mode }); } catch (e) {}
  };

  useEffect(() => {
    if (currentScreen === 'splash') {
      const timer = setTimeout(() => setCurrentScreen('onboarding'), 2200);
      return () => clearTimeout(timer);
    }
  }, [currentScreen]);

  return (
    <div className="flex justify-center items-center min-h-screen bg-black font-sans selection:bg-[#CEFF00]/30 antialiased">
      <style>{styles}</style>
      <div className="w-full sm:max-w-[420px] h-[100dvh] sm:h-[880px] bg-[#0A0B09] text-white sm:rounded-[48px] relative overflow-hidden flex flex-col border border-white/10 shadow-2xl">
        
        {currentScreen === 'splash' && <Splash />}
        {currentScreen === 'onboarding' && <Onboarding onComplete={() => { VpnPlugin.setOnboardingComplete(); setCurrentScreen('permissions'); }} />}
        {currentScreen === 'permissions' && <Permissions onComplete={() => { toggleProtection(true); setCurrentScreen('main'); }} />}
        {currentScreen === 'premium' && <Premium onBack={() => setCurrentScreen('main')} onSubscribe={() => { VpnPlugin.setPremium({ isPremium: true }); setIsPremium(true); setCurrentScreen('main'); }} />}
        
        {currentScreen === 'main' && (
          <MainLayout 
            activeTab={activeTab} setActiveTab={setActiveTab}
            isProtected={isProtected} setIsProtected={toggleProtection}
            currentMode={currentMode} setCurrentMode={setCurrentMode}
            currentProvider={currentProvider} setCurrentProvider={setCurrentProvider}
            stats={stats} recentLogs={recentLogs}
            isPremium={isPremium}
            activeSheet={activeSheet} setActiveSheet={setActiveSheet}
            updateConfig={updateConfig}
            setCurrentScreen={setCurrentScreen}
            isTestingSpeed={isTestingSpeed} setIsTestingSpeed={setIsTestingSpeed}
          />
        )}
      </div>
    </div>
  );
}

// --- SUB-COMPONENTS ---

function Splash() {
  return (
    <div className="flex flex-col items-center justify-center h-full animate-fade-in">
      <div className="relative mb-8">
        <div className="absolute inset-0 bg-[#CEFF00] blur-3xl opacity-20 animate-pulse" />
        <Logo className="w-24 h-24 relative z-10" />
      </div>
      <h1 className="text-2xl font-black tracking-[0.4em] uppercase text-white/90">Pickko</h1>
    </div>
  );
}

function Onboarding({ onComplete }: { onComplete: () => void }) {
  const [step, setStep] = useState(0);
  const slides = [
    { title: "Pure Internet.", desc: "Eliminate trackers and intrusive ads without sacrificing your connection speed.", icon: <Shield className="text-[#CEFF00] w-8 h-8" /> },
    { title: "Zero Logs.", desc: "Your data stays on your device. We prioritize your privacy above everything else.", icon: <Lock className="text-[#CEFF00] w-8 h-8" /> },
    { title: "Optimized Paths.", desc: "Choose between gaming, privacy, or balanced modes with a single tap.", icon: <Zap className="text-[#CEFF00] w-8 h-8" /> },
  ];
  return (
    <div className="flex flex-col h-full p-10 animate-fade-in">
      <div className="flex justify-end pt-2"><button onClick={onComplete} className="text-[#8E938A] font-bold text-xs uppercase tracking-widest">Skip</button></div>
      <div className="flex-1 flex flex-col justify-center">
        <div className="w-20 h-20 glass-card rounded-[28px] flex items-center justify-center mb-10 shadow-inner">{slides[step].icon}</div>
        <h2 className="text-4xl font-black mb-6 leading-tight">{slides[step].title}</h2>
        <p className="text-[#8E938A] text-lg leading-relaxed mb-16 font-medium">{slides[step].desc}</p>
        <div className="flex space-x-3 mb-12">{slides.map((_, i) => <div key={i} className={`h-1.5 rounded-full transition-all duration-500 ${i === step ? 'w-10 bg-[#CEFF00]' : 'w-3 bg-white/10'}`} />)}</div>
      </div>
      <button onClick={() => step < 2 ? setStep(step + 1) : onComplete()} className="w-full bg-[#CEFF00] text-[#0A0B09] py-5 rounded-[24px] font-black text-lg shadow-[0_10px_25px_rgba(206,255,0,0.2)] active:scale-[0.98] transition-transform">
        {step < 2 ? 'Continue' : "Get Started"}
      </button>
    </div>
  );
}

function Permissions({ onComplete }: { onComplete: () => void }) {
  return (
    <div className="flex flex-col h-full p-10 animate-scale-in">
      <div className="flex-1 flex flex-col justify-center items-center text-center">
        <div className="w-24 h-24 glass-panel rounded-full flex items-center justify-center mb-10 pulse-active"><Info className="text-[#CEFF00] w-10 h-10" /></div>
        <h2 className="text-3xl font-black mb-6">VPN Authorization</h2>
        <div className="glass-panel rounded-[32px] p-8 mb-10">
          <p className="text-[#8E938A] text-base leading-relaxed font-medium">
            Pickko requires a local loopback VPN to intercept and filter DNS requests on-device. 
            <span className="block mt-4 text-white/60 font-bold italic">No traffic ever leaves your phone.</span>
          </p>
        </div>
      </div>
      <button onClick={onComplete} className="w-full bg-[#CEFF00] text-[#0A0B09] py-5 rounded-[24px] font-black text-lg shadow-[0_10px_25px_rgba(206,255,0,0.2)] active:scale-[0.98] transition-transform mb-6">Establish Connection</button>
      <button className="w-full py-2 text-[#8E938A] text-xs font-bold uppercase tracking-widest opacity-60">Privacy Policy</button>
    </div>
  );
}

const MainLayout = memo(({ 
  activeTab, setActiveTab, isProtected, setIsProtected, 
  currentMode, setCurrentMode, currentProvider, setCurrentProvider,
  stats, recentLogs, isPremium, activeSheet, setActiveSheet, updateConfig,
  setCurrentScreen, isTestingSpeed, setIsTestingSpeed
}: any) => {
  return (
    <>
      <div className="flex-1 overflow-y-auto pb-32 scrollbar-hide">
        {activeTab === 'home' && <Home 
          isProtected={isProtected} setIsProtected={setIsProtected}
          currentMode={currentMode}
          currentProvider={currentProvider} openSheet={setActiveSheet}
          isPremium={isPremium} recentLogs={recentLogs}
          setCurrentMode={(m: string) => {
            setCurrentMode(m);
            let p = currentProvider;
            if (m === 'Gaming') p = 'Cloudflare';
            else if (m === 'Maximum Privacy') p = 'Quad9';
            else if (m === 'Family Safe') p = 'AdGuard Family';
            else if (m === 'Battery Saver') p = 'Cloudflare';
            setCurrentProvider(p);
            updateConfig(p, m);
          }}
        />}
        {activeTab === 'stats' && <StatsView stats={stats} />}
        {activeTab === 'tools' && <ToolsView isTestingSpeed={isTestingSpeed} setIsTestingSpeed={setIsTestingSpeed} />}
        {activeTab === 'settings' && <SettingsView openPremium={() => setCurrentScreen('premium')} />}
      </div>
      <BottomNav activeTab={activeTab} setActiveTab={setActiveTab} />
      <BottomSheets
        activeSheet={activeSheet} closeSheet={() => setActiveSheet(null)}
        currentProvider={currentProvider}
        setCurrentProvider={(p: string) => {
          setCurrentProvider(p);
          setCurrentMode('Balanced');
          updateConfig(p, 'Balanced');
        }}
        setIsProtected={setIsProtected}
      />
    </>
  );
});

function Home({ isProtected, setIsProtected, currentMode, setCurrentMode, currentProvider, openSheet, recentLogs }: any) {
  return (
    <div className="p-8 pt-12 animate-fade-in">
      <div className="flex items-center justify-between mb-10">
        <div className="flex items-center space-x-4">
          <div className="w-12 h-12 glass-card rounded-2xl flex items-center justify-center shadow-inner overflow-hidden">
            <Logo className="w-8 h-8" />
          </div>
          <div><h2 className="text-xl font-black">Apex Engine</h2><p className="text-[10px] font-black text-[#8E938A] uppercase tracking-widest">Active Protection</p></div>
        </div>
        <button onClick={() => openSheet('settings')} className="w-12 h-12 glass-card rounded-2xl flex items-center justify-center"><Settings className="w-6 h-6" /></button>
      </div>

      <div className={`rounded-[40px] p-8 mb-10 glass-panel transition-all duration-500 ${isProtected ? 'border-[#CEFF00]/40 shadow-[0_20px_50px_rgba(206,255,0,0.1)]' : ''}`}>
        <div className="flex justify-between items-start mb-12">
          <div>
            <div className="flex items-center space-x-2 mb-3">
              <div className={`w-2.5 h-2.5 rounded-full ${isProtected ? 'bg-[#CEFF00] shadow-[0_0_10px_#CEFF00]' : 'bg-[#FF5C5C]'}`} />
              <p className="text-[10px] font-black uppercase text-[#8E938A] tracking-[0.2em]">Current State</p>
            </div>
            <h3 className="text-3xl font-black leading-none">{isProtected ? 'Shielded' : 'Vulnerable'}</h3>
          </div>
          <button 
            onClick={() => setIsProtected(!isProtected)} 
            className={`w-[72px] h-[36px] rounded-full flex items-center p-1.5 transition-all duration-500 ${isProtected ? 'bg-[#CEFF00]' : 'bg-white/10'}`}
          >
            <div className={`w-6 h-6 rounded-full transition-all duration-500 shadow-sm ${isProtected ? 'bg-[#0A0B09] translate-x-9' : 'bg-[#8E938A]'}`} />
          </button>
        </div>
        <div className="flex space-x-3">
          <button onClick={() => openSheet('providers')} className="flex-1 flex items-center justify-between px-6 py-4 rounded-[24px] glass-card text-sm font-bold active:scale-[0.97] transition-transform">
            <div className="flex items-center space-x-3"><Globe className="w-4 h-4 text-[#CEFF00]" /><span>{currentProvider}</span></div>
            <ChevronRight className="w-4 h-4 opacity-40" />
          </button>
          {isProtected && <button onClick={() => openSheet('pause')} className="w-14 flex items-center justify-center glass-card rounded-[24px] active:scale-[0.97] transition-transform"><Pause className="w-5 h-5" /></button>}
        </div>
      </div>

      <div className="mb-10">
        <div className="flex items-center justify-between mb-5 px-1">
          <h3 className="text-[10px] font-black text-[#8E938A] uppercase tracking-[0.2em]">Smart Presets</h3>
        </div>
        <div className="flex space-x-4 overflow-x-auto scrollbar-hide pb-2 -mx-8 px-8">
          <ModeCard icon={<Shield />} title="Balanced" active={currentMode === 'Balanced'} onClick={() => setCurrentMode('Balanced')} />
          <ModeCard icon={<Lock />} title="Privacy" active={currentMode === 'Maximum Privacy'} onClick={() => setCurrentMode('Maximum Privacy')} />
          <ModeCard icon={<Zap />} title="Gaming" active={currentMode === 'Gaming'} onClick={() => setCurrentMode('Gaming')} />
          <ModeCard icon={<User />} title="Family" active={currentMode === 'Family Safe'} onClick={() => setCurrentMode('Family Safe')} />
          <ModeCard icon={<Battery />} title="Saver" active={currentMode === 'Battery Saver'} onClick={() => setCurrentMode('Battery Saver')} />
        </div>
      </div>

      <div className="mb-10">
        <div className="flex items-center justify-between mb-5 px-1">
          <h3 className="text-[10px] font-black text-[#8E938A] uppercase tracking-[0.2em]">Live Activity</h3>
          <History className="w-3.5 h-3.5 text-[#8E938A]" />
        </div>
        <div className="space-y-3 pb-20">
          {recentLogs.length > 0 ? recentLogs.map((log: string, i: number) => {
            const isBlocked = log.startsWith("BLOCKED:");
            const hostname = log.replace(/^(BLOCKED:|RESOLVED:)/, "");
            return (
              <ActivityRow
                key={i}
                icon={isBlocked ? <ShieldAlert className="text-[#FF5C5C] w-5 h-5" /> : <ShieldCheck className="text-[#CEFF00] w-5 h-5" />}
                title={isBlocked ? "Threat Neutralized" : "DNS Request Handled"}
                subtitle={hostname}
                time="Just Now"
              />
            );
          }) : <div className="p-8 text-center glass-card rounded-[32px] opacity-40"><p className="text-xs font-bold uppercase tracking-widest italic">Monitoring stream...</p></div>}
        </div>
      </div>
    </div>
  );
}

function ModeCard({ icon, title, active, onClick }: any) {
  return (
    <button onClick={onClick} className={`min-w-[100px] h-[110px] rounded-[32px] p-4 flex flex-col items-center justify-center space-y-3 shrink-0 transition-all duration-300 ${active ? 'bg-[#CEFF00] text-[#0A0B09] shadow-[0_15px_30px_rgba(206,255,0,0.15)]' : 'glass-panel active:bg-white/5'}`}>
      <div className={active ? 'text-[#0A0B09]' : 'text-[#8E938A]'}>{React.cloneElement(icon as any, { className: "w-7 h-7", strokeWidth: 2.5 })}</div>
      <span className="text-xs font-black tracking-tight">{title}</span>
    </button>
  );
}

function ActivityRow({ icon, title, subtitle, time }: any) {
  return (
    <div className="glass-card rounded-[28px] p-4 flex items-center justify-between animate-fade-in group active:scale-[0.98] transition-transform">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-black/40 rounded-2xl flex items-center justify-center group-hover:scale-110 transition-transform">{icon}</div>
        <div className="overflow-hidden"><p className="font-black text-sm">{title}</p><p className="text-[10px] font-bold text-[#8E938A] truncate max-w-[180px]">{subtitle}</p></div>
      </div>
      <span className="text-[9px] font-black text-[#8E938A] uppercase tracking-widest opacity-50 shrink-0">{time}</span>
    </div>
  );
}

function StatsView({ stats }: any) {
  return (
    <div className="p-8 pt-12 animate-fade-in">
      <h2 className="text-3xl font-black mb-10 tracking-tight">Analytics</h2>
      <div className="grid grid-cols-2 gap-4">
        <StatCard title="Total Queries" value={stats.totalQueries} icon={<Globe className="w-4 h-4" />} />
        <StatCard title="Ads Blocked" value={stats.adsBlocked} icon={<ShieldAlert className="text-[#FF5C5C] w-4 h-4" />} />
        <StatCard title="Trackers" value={stats.trackers} icon={<Lock className="w-4 h-4" />} />
        <StatCard title="Data Saved" value={stats.dataSaved} icon={<Battery className="text-[#CEFF00] w-4 h-4" />} />
      </div>
      <div className="mt-10 glass-panel rounded-[36px] p-8 text-center border-dashed border-white/10">
        <Activity className="w-10 h-10 text-[#CEFF00] mx-auto mb-6 opacity-80" />
        <p className="font-black text-lg mb-2">Operational Insight</p>
        <p className="text-xs text-[#8E938A] leading-relaxed font-medium">Session metrics are volatile and reset upon shield toggle to ensure fresh performance monitoring.</p>
      </div>
    </div>
  );
}

function StatCard({ title, value, icon }: any) {
  return (
    <div className="glass-panel rounded-[32px] p-6 flex flex-col justify-between h-36">
      <div className="flex items-center justify-between opacity-60">
        <p className="text-[10px] font-black uppercase tracking-widest">{title}</p>
        {icon}
      </div>
      <p className="text-3xl font-black tracking-tighter">{value}</p>
    </div>
  );
}

function ToolsView({ isTestingSpeed, setIsTestingSpeed }: any) {
  const [ping, setPing] = useState<string | null>(null);
  const runTest = async () => {
    setIsTestingSpeed(true); setPing(null);
    try { 
      const r = await VpnPlugin.testLatency(); 
      setPing(r.latency); 
    } catch (e) { 
      setPing("err"); 
    } finally { 
      setIsTestingSpeed(false); 
    }
  };
  return (
    <div className="p-8 pt-12 animate-fade-in">
      <h2 className="text-3xl font-black mb-10 tracking-tight">System Tools</h2>
      <div className="space-y-4">
        <ToolCard icon={<Gauge />} title="Latency Probe" desc="Measure real-time resolution speed" action={<button onClick={runTest} className={`px-6 py-3 rounded-2xl text-xs font-black uppercase tracking-widest transition-all ${ping ? 'glass-card text-[#CEFF00]' : 'bg-[#CEFF00] text-[#0A0B09]'}`}>{isTestingSpeed ? 'Probing...' : ping || 'Execute'}</button>} />
        <ToolCard icon={<ShieldCheck />} title="Privacy Audit" desc="Check for external DNS leaks" action={<button onClick={() => window.open('https://dnsleaktest.com')} className="w-12 h-12 glass-card rounded-2xl flex items-center justify-center"><ChevronRight className="w-5 h-5 text-[#8E938A]" /></button>} />
        <ToolCard icon={<Wifi />} title="Route Tracker" desc="Analyze current network backbone" action={<button onClick={() => window.open('https://whoer.net')} className="w-12 h-12 glass-card rounded-2xl flex items-center justify-center"><ChevronRight className="w-5 h-5 text-[#8E938A]" /></button>} />
      </div>
    </div>
  );
}

function ToolCard({ icon, title, desc, action }: any) {
  return (
    <div className="glass-panel rounded-[32px] p-5 flex items-center justify-between border-l-4 border-l-transparent hover:border-l-[#CEFF00] transition-all">
      <div className="flex items-center space-x-5">
        <div className="w-14 h-14 bg-black/40 rounded-2xl flex items-center justify-center text-[#8E938A]">{icon}</div>
        <div><p className="font-black text-sm mb-1">{title}</p><p className="text-[10px] font-bold text-[#8E938A] uppercase tracking-wide">{desc}</p></div>
      </div>
      {action}
    </div>
  );
}

function SettingsView({ openPremium }: any) {
  return (
    <div className="p-8 pt-12 animate-fade-in pb-20">
      <h2 className="text-3xl font-black mb-10 tracking-tight">Parameters</h2>
      <div className="space-y-8">
        <SettingsGroup title="Membership">
          <div onClick={openPremium} className="p-5 flex items-center justify-between cursor-pointer active:bg-white/5 transition-colors">
            <div className="flex items-center space-x-4"><Crown className="w-6 h-6 text-[#CEFF00]" /><div><p className="font-black text-sm">Tier Status</p><p className="text-[10px] text-[#8E938A] font-bold">Standard Account</p></div></div>
            <ChevronRight className="w-4 h-4 text-[#8E938A]" />
          </div>
        </SettingsGroup>
        <SettingsGroup title="Core System">
          <SettingToggle title="Auto-Boot Protection" defaultChecked={true} />
          <SettingRow title="Battery Optimization" onClick={() => VpnPlugin.requestBatteryOptimizationExemption()} />
        </SettingsGroup>
        <SettingsGroup title="Information">
          <SettingRow title="Legal & Privacy" onClick={() => window.open('https://www.pickko.com/privacy', '_blank')} />
          <SettingRow title="Build Version" desc="v4.0.0 (Obsidian Build)" />
        </SettingsGroup>
      </div>
      <div className="mt-16 text-center">
        <div className="w-12 h-1 bg-white/5 mx-auto mb-8 rounded-full" />
        <p className="text-[#8E938A] text-[9px] uppercase font-black tracking-[0.3em] mb-6 opacity-40">Architected with love by Pickko ☕</p>
        <button onClick={() => window.open('https://www.supportkori.com/mdzobaedislamshanto')} className="glass-card px-10 py-4 rounded-[24px] text-xs font-black uppercase tracking-widest border-[#CEFF00]/10 hover:border-[#CEFF00]/30 transition-all">Support Portal</button>
      </div>
    </div>
  );
}

function SettingsGroup({ title, children }: any) {
  return (
    <div className="animate-fade-in">
      <h3 className="text-[10px] font-black text-[#8E938A] uppercase mb-4 px-4 tracking-[0.2em]">{title}</h3>
      <div className="glass-panel rounded-[36px] overflow-hidden divide-y divide-white/5">{children}</div>
    </div>
  );
}

function SettingToggle({ title, defaultChecked }: any) {
  const [checked, setChecked] = useState(defaultChecked);
  return (
    <div className="p-5 flex items-center justify-between">
      <span className="font-black text-sm">{title}</span>
      <button onClick={() => setChecked(!checked)} className={`w-14 h-[32px] rounded-full flex items-center p-1.5 transition-all duration-300 ${checked ? 'bg-[#CEFF00]' : 'bg-white/10'}`}>
        <div className={`w-5 h-5 rounded-full transition-all duration-300 shadow-sm ${checked ? 'bg-[#0A0B09] translate-x-7' : 'bg-[#8E938A]'}`} />
      </button>
    </div>
  );
}

function SettingRow({ title, onClick, desc }: any) {
  return (
    <div className="p-5 flex items-center justify-between active:bg-white/5 cursor-pointer" onClick={onClick}>
      <div><span className="font-black text-sm">{title}</span>{desc && <p className="text-[10px] text-[#8E938A] font-bold mt-0.5">{desc}</p>}</div>
      <ChevronRight className="w-4 h-4 text-[#8E938A]" />
    </div>
  );
}

function Premium({ onBack, onSubscribe }: any) {
  return (
    <div className="flex flex-col h-full bg-[#0A0B09] animate-spring-up relative z-50">
      <div className="p-8 pt-10 flex justify-between"><button onClick={onBack} className="w-14 h-14 glass-card rounded-2xl flex items-center justify-center active:scale-90 transition-transform"><X className="w-6 h-6" /></button></div>
      <div className="flex-1 px-10">
        <div className="w-20 h-20 glass-panel rounded-[32px] flex items-center justify-center mb-10 shadow-[0_0_50px_rgba(206,255,0,0.2)]"><Crown className="w-10 h-10 text-[#CEFF00]" /></div>
        <h2 className="text-4xl font-black mb-4 leading-tight">Elevated Protection</h2>
        <p className="text-[#8E938A] mb-12 text-lg font-medium leading-relaxed">Unlock advanced shielding and support independent, privacy-first software development.</p>
        <div className="space-y-8">
          <PremiumFeature icon={<ShieldCheck />} title="Complete Ad Exclusion" desc="System-wide removal of application-layer advertisements." />
          <PremiumFeature icon={<Zap />} title="Priority Routing" desc="Low-latency DNS paths specifically for gaming clusters." />
        </div>
      </div>
      <div className="p-8 pb-12"><button onClick={onSubscribe} className="w-full bg-[#CEFF00] text-[#0A0B09] py-6 rounded-[28px] font-black text-lg shadow-[0_15px_40px_rgba(206,255,0,0.3)] active:scale-[0.98] transition-transform">Activate Premium</button></div>
    </div>
  );
}

function PremiumFeature({ icon, title, desc }: any) {
  return (
    <div className="flex items-start space-x-5 animate-fade-in">
      <div className="w-12 h-12 rounded-2xl glass-card flex items-center justify-center text-[#CEFF00] shrink-0 mt-1">{icon}</div>
      <div><p className="font-black text-lg text-white mb-1">{title}</p><p className="text-sm text-[#8E938A] font-medium leading-relaxed">{desc}</p></div>
    </div>
  );
}

function BottomNav({ activeTab, setActiveTab }: any) {
  return (
    <div className="absolute bottom-10 left-8 right-8 bg-[#1A1C18]/60 backdrop-blur-3xl rounded-[32px] px-8 py-5 flex justify-between items-center border border-white/10 shadow-[0_20px_50px_rgba(0,0,0,0.5)]">
      <NavItem icon={<Shield />} active={activeTab === 'home'} onClick={() => setActiveTab('home')} />
      <NavItem icon={<BarChart3 />} active={activeTab === 'stats'} onClick={() => setActiveTab('stats')} />
      <NavItem icon={<Wrench />} active={activeTab === 'tools'} onClick={() => setActiveTab('tools')} />
      <NavItem icon={<Settings />} active={activeTab === 'settings'} onClick={() => setActiveTab('settings')} />
    </div>
  );
}

function NavItem({ icon, active, onClick }: any) {
  return (
    <button onClick={onClick} className={`relative p-3 rounded-2xl transition-all duration-300 ${active ? 'text-[#CEFF00] bg-white/5' : 'text-[#8E938A]'}`}>
      {React.cloneElement(icon as any, { strokeWidth: active ? 3 : 2, className: "w-6 h-6" })}
      {active && <span className="absolute -bottom-1 left-1/2 -translate-x-1/2 w-1.5 h-1.5 bg-[#CEFF00] rounded-full shadow-[0_0_8px_#CEFF00]" />}
    </button>
  );
}

function BottomSheets({ activeSheet, closeSheet, currentProvider, setCurrentProvider, setIsProtected }: any) {
  if (!activeSheet) return null;
  return (
    <div className="absolute inset-0 z-50 flex flex-col justify-end">
      <div className="absolute inset-0 bg-black/80 backdrop-blur-md animate-fade-in" onClick={closeSheet} />
      <div className="bg-[#0D0E0C] rounded-t-[48px] relative z-10 animate-spring-up border-t border-white/10 p-10 pb-16">
        <div className="w-16 h-1.5 bg-white/10 rounded-full mx-auto mb-10" />
        
        {activeSheet === 'providers' && (
          <div className="space-y-3">
            <h3 className="text-[10px] font-black text-[#8E938A] uppercase mb-6 px-2 tracking-[0.3em] text-center">Select Provider</h3>
            <SelectionRow title="AdGuard DNS" icon={<ShieldCheck />} active={currentProvider === 'AdGuard DNS'} onClick={() => { setCurrentProvider('AdGuard DNS'); closeSheet(); }} />
            <SelectionRow title="Cloudflare" icon={<Globe />} active={currentProvider === 'Cloudflare'} onClick={() => { setCurrentProvider('Cloudflare'); closeSheet(); }} />
            <SelectionRow title="NextDNS" icon={<Settings />} active={currentProvider === 'NextDNS'} onClick={() => { setCurrentProvider('NextDNS'); closeSheet(); }} />
            <SelectionRow title="Quad9" icon={<Lock />} active={currentProvider === 'Quad9'} onClick={() => { setCurrentProvider('Quad9'); closeSheet(); }} />
          </div>
        )}
        
        {activeSheet === 'pause' && (
          <div className="text-center py-4">
            <div className="w-20 h-20 glass-panel rounded-full flex items-center justify-center mx-auto mb-8 border-[#FF5C5C]/20 shadow-[0_0_40px_rgba(255,92,92,0.1)]"><Pause className="w-10 h-10 text-[#FF5C5C]" /></div>
            <h3 className="text-2xl font-black mb-4">Deactivate Shield?</h3>
            <p className="text-[#8E938A] mb-10 text-sm font-medium">Your device will be exposed to trackers and unwanted advertisements.</p>
            <button onClick={() => { setIsProtected(false); closeSheet(); }} className="w-full bg-[#FF5C5C]/10 text-[#FF5C5C] py-5 rounded-[24px] font-black text-lg active:scale-[0.98] transition-transform">Confirm Deactivation</button>
            <button onClick={closeSheet} className="w-full py-4 text-[#8E938A] font-black text-xs uppercase tracking-widest mt-4">Cancel</button>
          </div>
        )}
      </div>
    </div>
  );
}

function SelectionRow({ title, icon, active, onClick }: any) {
  return (
    <button onClick={onClick} className={`w-full p-5 rounded-[28px] flex items-center justify-between border-2 transition-all duration-300 ${active ? 'bg-[#CEFF00]/10 border-[#CEFF00]/30 shadow-inner' : 'glass-card border-transparent active:bg-white/5'}`}>
      <div className="flex items-center space-x-5">
        <div className={`w-12 h-12 rounded-2xl flex items-center justify-center transition-colors ${active ? 'bg-[#CEFF00] text-[#0A0B09]' : 'bg-black/40 text-[#8E938A]'}`}>{icon}</div>
        <p className={`font-black text-base ${active ? 'text-[#CEFF00]' : ''}`}>{title}</p>
      </div>
      {active && <CheckCircle2 className="w-6 h-6 text-[#CEFF00]" />}
    </button>
  );
}
