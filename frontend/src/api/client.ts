const API_URL = import.meta.env.VITE_API_URL as string | undefined;

const TOKEN_KEY = 'gradetrack.token';
const ROLE_KEY = 'gradetrack.role';

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function request<TResponse>(
  path: string,
  init: { method: string; body?: unknown; auth?: boolean },
): Promise<TResponse> {
  if (!API_URL) {
    throw new ApiError('VITE_API_URL is not configured — copy .env.example to .env', 0);
  }

  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (init.auth) {
    const token = getToken();
    if (!token) {
      throw new ApiError('Not logged in', 401);
    }
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${API_URL}${path}`, {
    method: init.method,
    headers,
    body: init.body ? JSON.stringify(init.body) : undefined,
  });

  const payload = await response.json().catch(() => ({}));

  if (!response.ok) {
    throw new ApiError(payload.error ?? 'Something went wrong', response.status);
  }

  return payload as TResponse;
}

function post<TResponse>(path: string, body: unknown) {
  return request<TResponse>(path, { method: 'POST', body });
}

function get<TResponse>(path: string) {
  return request<TResponse>(path, { method: 'GET', auth: true });
}

function authPost<TResponse>(path: string, body?: unknown) {
  return request<TResponse>(path, { method: 'POST', body, auth: true });
}

function del<TResponse>(path: string) {
  return request<TResponse>(path, { method: 'DELETE', auth: true });
}

function put<TResponse>(path: string, body?: unknown) {
  return request<TResponse>(path, { method: 'PUT', body, auth: true });
}

export interface AuthResult {
  userId: string;
  token: string;
  role?: string;
}

export function signup(email: string, password: string, firstName: string, lastName: string) {
  return post<AuthResult>('/auth/signup', { email, password, firstName, lastName });
}

export function login(email: string, password: string) {
  return post<AuthResult>('/auth/login', { email, password });
}

export interface Student {
  id: string;
  firstName: string;
  lastName: string;
  schoolName: string | null;
}

export interface TermGrade {
  courseName: string;
  termName: string;
  teacherName: string;
  finalPct: number | null;
  finalLetter: string | null;
}

export async function getStudents(): Promise<Student[]> {
  const result = await get<{ students: Student[] }>('/students');
  return result.students;
}

export async function getStudentGrades(studentId: string): Promise<TermGrade[]> {
  const result = await get<{ grades: TermGrade[] }>(`/students/${studentId}/grades`);
  return result.grades;
}

export type TriggerType = 'grade_below' | 'missing_assignment' | 'grade_drop_pct' | 'report_card_posted';
export type AlertChannel = 'email' | 'sms' | 'push' | 'in_app';

export interface AlertRule {
  id: string;
  studentId: string | null;
  triggerType: TriggerType;
  thresholdValue: number | null;
  channel: AlertChannel;
  active: boolean;
}

export interface Notification {
  id: string;
  message: string;
  relatedEntityType: string | null;
  relatedEntityId: string | null;
  readAt: string | null;
  createdAt: string;
}

export async function getAlertRules(): Promise<AlertRule[]> {
  const result = await get<{ rules: AlertRule[] }>('/alerts/rules');
  return result.rules;
}

export function createAlertRule(rule: {
  studentId?: string;
  triggerType: TriggerType;
  thresholdValue?: number;
  channel: AlertChannel;
}) {
  return authPost<{ id: string }>('/alerts/rules', rule);
}

export function deleteAlertRule(ruleId: string) {
  return del<{ deleted: number }>(`/alerts/rules/${ruleId}`);
}

export async function getNotifications(): Promise<Notification[]> {
  const result = await get<{ notifications: Notification[] }>('/alerts/notifications');
  return result.notifications;
}

export function markNotificationRead(notificationId: string) {
  return authPost<{ read: boolean }>(`/alerts/notifications/${notificationId}/read`);
}

export function saveToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ROLE_KEY);
}

export function saveRole(role: string) {
  localStorage.setItem(ROLE_KEY, role);
}

export function getRole(): string | null {
  return localStorage.getItem(ROLE_KEY);
}

// ---- Admin: org setup ----

export interface School {
  id: string;
  name: string;
  districtName: string | null;
}

export interface AcademicYear {
  id: string;
  name: string;
  startDate: string;
  endDate: string;
  schoolId: string;
  schoolName: string;
}

export interface Term {
  id: string;
  name: string;
  termType: string;
  startDate: string;
  endDate: string;
  academicYearId: string;
  academicYearName: string;
}

export interface Course {
  id: string;
  name: string;
  subject: string | null;
  courseCode: string | null;
  schoolId: string;
}

export interface Teacher {
  id: string;
  firstName: string;
  lastName: string;
  email: string | null;
  schoolId: string;
}

export interface Section {
  id: string;
  courseName: string;
  termName: string;
  teacherName: string;
  period: string | null;
  room: string | null;
}

export const getSchools = () => get<{ schools: School[] }>('/admin/schools').then((r) => r.schools);
export const createSchool = (body: { name: string; districtName?: string; address?: string }) =>
  authPost<{ id: string }>('/admin/schools', body);
export const updateSchool = (id: string, body: { name?: string; districtName?: string; address?: string }) =>
  put<{ updated: boolean }>(`/admin/schools/${id}`, body);
export const deleteSchool = (id: string) => del<{ deleted: boolean }>(`/admin/schools/${id}`);

export const getAcademicYears = () =>
  get<{ academicYears: AcademicYear[] }>('/admin/academic-years').then((r) => r.academicYears);
export const createAcademicYear = (body: {
  schoolId: string;
  name: string;
  startDate: string;
  endDate: string;
}) => authPost<{ id: string }>('/admin/academic-years', body);
export const updateAcademicYear = (
  id: string,
  body: { schoolId?: string; name?: string; startDate?: string; endDate?: string },
) => put<{ updated: boolean }>(`/admin/academic-years/${id}`, body);
export const deleteAcademicYear = (id: string) => del<{ deleted: boolean }>(`/admin/academic-years/${id}`);

export const getTerms = () => get<{ terms: Term[] }>('/admin/terms').then((r) => r.terms);
export const createTerm = (body: {
  academicYearId: string;
  name: string;
  termType: string;
  startDate: string;
  endDate: string;
}) => authPost<{ id: string }>('/admin/terms', body);
export const updateTerm = (
  id: string,
  body: { name?: string; termType?: string; startDate?: string; endDate?: string },
) => put<{ updated: boolean }>(`/admin/terms/${id}`, body);
export const deleteTerm = (id: string) => del<{ deleted: boolean }>(`/admin/terms/${id}`);

export const getCourses = () => get<{ courses: Course[] }>('/admin/courses').then((r) => r.courses);
export const createCourse = (body: { schoolId: string; name: string; subject?: string; courseCode?: string }) =>
  authPost<{ id: string }>('/admin/courses', body);
export const updateCourse = (id: string, body: { name?: string; subject?: string; courseCode?: string }) =>
  put<{ updated: boolean }>(`/admin/courses/${id}`, body);
export const deleteCourse = (id: string) => del<{ deleted: boolean }>(`/admin/courses/${id}`);

export const getTeachers = () => get<{ teachers: Teacher[] }>('/admin/teachers').then((r) => r.teachers);
export const createTeacher = (body: { schoolId: string; firstName: string; lastName: string; email?: string }) =>
  authPost<{ id: string }>('/admin/teachers', body);
export const linkTeacherAccount = (teacherId: string, email: string) =>
  authPost<{ linked: boolean }>(`/admin/teachers/${teacherId}/link-account`, { email });
export const updateTeacher = (
  id: string,
  body: { firstName?: string; lastName?: string; email?: string },
) => put<{ updated: boolean }>(`/admin/teachers/${id}`, body);
export const deleteTeacher = (id: string) => del<{ deleted: boolean }>(`/admin/teachers/${id}`);

export const getSections = () => get<{ sections: Section[] }>('/admin/sections').then((r) => r.sections);
export const createSection = (body: {
  courseId: string;
  termId: string;
  teacherId: string;
  period?: string;
  room?: string;
}) => authPost<{ id: string }>('/admin/sections', body);
export const updateSection = (
  id: string,
  body: { teacherId?: string; period?: string; room?: string },
) => put<{ updated: boolean }>(`/admin/sections/${id}`, body);
export const deleteSection = (id: string) => del<{ deleted: boolean }>(`/admin/sections/${id}`);

// ---- Admin: students, guardians, enrollment ----

export interface AdminStudent {
  id: string;
  firstName: string;
  lastName: string;
  dateOfBirth: string | null;
  schoolName: string | null;
}

export interface Enrollment {
  id: string;
  studentId: string;
  sectionId: string;
  studentName: string;
  courseName: string;
  status: string;
}

export interface Guardian {
  guardianId: string;
  guardianEmail: string;
  relationship: string;
  isPrimary: boolean;
  accessLevel: string;
}

export const getAdminStudents = () =>
  get<{ students: AdminStudent[] }>('/admin/students').then((r) => r.students);
export const createAdminStudent = (body: {
  firstName: string;
  lastName: string;
  dateOfBirth?: string;
  currentSchoolId?: string;
}) => authPost<{ id: string }>('/admin/students', body);
export const updateAdminStudent = (
  id: string,
  body: { firstName?: string; lastName?: string; dateOfBirth?: string; currentSchoolId?: string },
) => put<{ updated: boolean }>(`/admin/students/${id}`, body);
export const deleteAdminStudent = (id: string) => del<{ deleted: boolean }>(`/admin/students/${id}`);

export const linkGuardian = (
  studentId: string,
  body: { guardianEmail: string; relationship: string; isPrimary?: boolean; accessLevel?: string },
) => authPost<{ id: string }>(`/admin/students/${studentId}/guardians`, body);

export const getGuardians = (studentId: string) =>
  get<{ guardians: Guardian[] }>(`/admin/students/${studentId}/guardians`).then((r) => r.guardians);
export const revokeGuardian = (studentId: string, guardianId: string) =>
  del<{ deleted: boolean }>(`/admin/students/${studentId}/guardians/${guardianId}`);

export const getEnrollments = () =>
  get<{ enrollments: Enrollment[] }>('/admin/enrollments').then((r) => r.enrollments);
export const createEnrollment = (body: { studentId: string; sectionId: string; enrolledOn?: string }) =>
  authPost<{ id: string }>('/admin/enrollments', body);
export const deleteEnrollment = (id: string) => del<{ deleted: boolean }>(`/admin/enrollments/${id}`);

// ---- Admin: gradebook ----

export interface AssignmentCategory {
  id: string;
  name: string;
  weightPct: number;
}

export interface AdminAssignment {
  id: string;
  name: string;
  pointsPossible: number;
  dueDate: string | null;
  categoryName: string;
}

export const getCategories = (sectionId: string) =>
  get<{ categories: AssignmentCategory[] }>(`/admin/sections/${sectionId}/categories`).then((r) => r.categories);
export const createCategory = (sectionId: string, body: { name: string; weightPct: number }) =>
  authPost<{ id: string }>(`/admin/sections/${sectionId}/categories`, body);
export const updateCategory = (
  sectionId: string,
  categoryId: string,
  body: { name?: string; weightPct?: number },
) => put<{ updated: boolean }>(`/admin/sections/${sectionId}/categories/${categoryId}`, body);
export const deleteCategory = (sectionId: string, categoryId: string) =>
  del<{ deleted: boolean }>(`/admin/sections/${sectionId}/categories/${categoryId}`);

export const getAssignments = (sectionId: string) =>
  get<{ assignments: AdminAssignment[] }>(`/admin/sections/${sectionId}/assignments`).then((r) => r.assignments);
export const createAssignment = (
  sectionId: string,
  body: {
    categoryId: string;
    name: string;
    description?: string;
    pointsPossible: number;
    assignedDate?: string;
    dueDate?: string;
  },
) => authPost<{ id: string }>(`/admin/sections/${sectionId}/assignments`, body);
export const updateAssignment = (
  sectionId: string,
  assignmentId: string,
  body: {
    categoryId?: string;
    name?: string;
    description?: string;
    pointsPossible?: number;
    assignedDate?: string;
    dueDate?: string;
  },
) => put<{ updated: boolean }>(`/admin/sections/${sectionId}/assignments/${assignmentId}`, body);
export const deleteAssignment = (sectionId: string, assignmentId: string) =>
  del<{ deleted: boolean }>(`/admin/sections/${sectionId}/assignments/${assignmentId}`);

export const upsertAssignmentGrade = (
  assignmentId: string,
  studentId: string,
  body: { pointsEarned?: number; status?: string; teacherComment?: string },
) => put<{ id: string }>(`/admin/assignments/${assignmentId}/grades/${studentId}`, body);

export const upsertTermGrade = (
  sectionId: string,
  studentId: string,
  body: { termId: string; finalPct?: number; finalLetter?: string; gpaPoints?: number; posted?: boolean },
) => put<{ id: string }>(`/admin/sections/${sectionId}/term-grades/${studentId}`, body);

// ---- Teacher console ----
// Same shapes as the admin gradebook calls above, scoped to /teacher/* — the backend
// verifies every section/assignment actually belongs to the calling teacher.

export interface TeacherSection {
  id: string;
  courseName: string;
  termId: string;
  termName: string;
  period: string | null;
  room: string | null;
}

export interface RosterStudent {
  id: string;
  firstName: string;
  lastName: string;
}

export const getTeacherSections = () =>
  get<{ sections: TeacherSection[] }>('/teacher/sections').then((r) => r.sections);

export const getTeacherRoster = (sectionId: string) =>
  get<{ roster: RosterStudent[] }>(`/teacher/sections/${sectionId}/roster`).then((r) => r.roster);

export const getTeacherCategories = (sectionId: string) =>
  get<{ categories: AssignmentCategory[] }>(`/teacher/sections/${sectionId}/categories`).then(
    (r) => r.categories,
  );
export const createTeacherCategory = (sectionId: string, body: { name: string; weightPct: number }) =>
  authPost<{ id: string }>(`/teacher/sections/${sectionId}/categories`, body);
export const updateTeacherCategory = (
  sectionId: string,
  categoryId: string,
  body: { name?: string; weightPct?: number },
) => put<{ updated: boolean }>(`/teacher/sections/${sectionId}/categories/${categoryId}`, body);
export const deleteTeacherCategory = (sectionId: string, categoryId: string) =>
  del<{ deleted: boolean }>(`/teacher/sections/${sectionId}/categories/${categoryId}`);

export const getTeacherAssignments = (sectionId: string) =>
  get<{ assignments: AdminAssignment[] }>(`/teacher/sections/${sectionId}/assignments`).then(
    (r) => r.assignments,
  );
export const createTeacherAssignment = (
  sectionId: string,
  body: {
    categoryId: string;
    name: string;
    description?: string;
    pointsPossible: number;
    assignedDate?: string;
    dueDate?: string;
  },
) => authPost<{ id: string }>(`/teacher/sections/${sectionId}/assignments`, body);
export const updateTeacherAssignment = (
  sectionId: string,
  assignmentId: string,
  body: {
    categoryId?: string;
    name?: string;
    description?: string;
    pointsPossible?: number;
    assignedDate?: string;
    dueDate?: string;
  },
) => put<{ updated: boolean }>(`/teacher/sections/${sectionId}/assignments/${assignmentId}`, body);
export const deleteTeacherAssignment = (sectionId: string, assignmentId: string) =>
  del<{ deleted: boolean }>(`/teacher/sections/${sectionId}/assignments/${assignmentId}`);

export const upsertTeacherAssignmentGrade = (
  assignmentId: string,
  studentId: string,
  body: { pointsEarned?: number; status?: string; teacherComment?: string },
) => put<{ id: string }>(`/teacher/assignments/${assignmentId}/grades/${studentId}`, body);

export const upsertTeacherTermGrade = (
  sectionId: string,
  studentId: string,
  body: { termId: string; finalPct?: number; finalLetter?: string; gpaPoints?: number; posted?: boolean },
) => put<{ id: string }>(`/teacher/sections/${sectionId}/term-grades/${studentId}`, body);
