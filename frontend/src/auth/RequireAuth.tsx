import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { getToken } from '../api/client';

export default function RequireAuth({ children }: { children: ReactNode }) {
  if (!getToken()) {
    return <Navigate to="/login" replace />;
  }
  return <>{children}</>;
}
