import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { getRole, getToken } from '../api/client';

export default function RequireAdmin({ children }: { children: ReactNode }) {
  if (!getToken()) {
    return <Navigate to="/login" replace />;
  }
  if (getRole() !== 'admin') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}
