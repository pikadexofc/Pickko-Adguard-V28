import React from 'react';

export const Logo = ({ className = "w-12 h-12", color = "#CEFF00" }: { className?: string; color?: string }) => {
  return (
    <svg 
      viewBox="0 0 100 100" 
      fill="none" 
      xmlns="http://www.w3.org/2000/svg" 
      className={className}
    >
      {/* Outer Shield Glow (Subtle) */}
      <defs>
        <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation="3" result="blur" />
          <feComposite in="SourceGraphic" in2="blur" operator="over" />
        </filter>
        <linearGradient id="shieldGradient" x1="50" y1="0" x2="50" y2="100" gradientUnits="userSpaceOnUse">
          <stop stopColor={color} />
          <stop offset="1" stopColor="#9EFF00" />
        </linearGradient>
      </defs>

      {/* Main Shield Body */}
      <path 
        d="M50 5L15 20V45C15 67.5 30 88 50 95C70 88 85 67.5 85 45V20L50 5Z" 
        fill="url(#shieldGradient)" 
        fillOpacity="0.15"
        stroke={color}
        strokeWidth="4"
        strokeLinejoin="round"
      />

      {/* The "Apex" A-Symbol */}
      <path 
        d="M35 55L45 65L65 35" 
        stroke={color} 
        strokeWidth="8" 
        strokeLinecap="round" 
        strokeLinejoin="round"
        filter="url(#glow)"
      />
      
      {/* Subtle Bottom Accent */}
      <path 
        d="M40 80C43 81.5 46.5 82.5 50 82.5C53.5 82.5 57 81.5 60 80" 
        stroke={color} 
        strokeWidth="2" 
        strokeLinecap="round" 
        opacity="0.5"
      />
    </svg>
  );
};

export default Logo;
