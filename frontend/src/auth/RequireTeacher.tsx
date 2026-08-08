import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { getRole, getToken } from '../api/client';

export default function RequireTeacher({ children }: { children: ReactNode }) {
  if (!getToken()) {
    return <Navigate to="/login" replace />;
  }
  if (getRole() !== 'teacher') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}
