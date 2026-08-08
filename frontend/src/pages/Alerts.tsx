import { type FormEvent, useEffect, useState } from 'react';
import {
  type AlertChannel,
  type AlertRule,
  ApiError,
  createAlertRule,
  deleteAlertRule,
  getAlertRules,
  getNotifications,
  getStudents,
  markNotificationRead,
  type Notification,
  type Student,
  type TriggerType,
} from '../api/client';

const TRIGGER_LABELS: Record<TriggerType, string> = {
  grade_below: 'Grade drops below a percentage',
  missing_assignment: 'An assignment is marked missing',
  grade_drop_pct: 'Grade drops by a percentage',
  report_card_posted: 'A report card is posted',
};

const CHANNEL_LABELS: Record<AlertChannel, string> = {
  email: 'Email',
  sms: 'Text message',
  push: 'Push notification',
  in_app: 'In-app only',
};

export default function Alerts() {
  const [students, setStudents] = useState<Student[]>([]);
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [notifications, setNotifications] = useState<Notification[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [triggerType, setTriggerType] = useState<TriggerType>('grade_below');
  const [threshold, setThreshold] = useState('70');
  const [channel, setChannel] = useState<AlertChannel>('in_app');
  const [studentId, setStudentId] = useState('');
  const [submitting, setSubmitting] = useState(false);

  function loadAll() {
    Promise.all([getStudents(), getAlertRules(), getNotifications()])
      .then(([s, r, n]) => {
        setStudents(s);
        setRules(r);
        setNotifications(n);
      })
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load alerts'));
  }

  useEffect(loadAll, []);

  function studentName(id: string | null) {
    if (!id) return 'All children';
    const student = students.find((s) => s.id === id);
    return student ? `${student.firstName} ${student.lastName}` : 'Unknown student';
  }

  async function handleMarkRead(id: string) {
    try {
      await markNotificationRead(id);
      setNotifications((prev) =>
        prev ? prev.map((n) => (n.id === id ? { ...n, readAt: new Date().toISOString() } : n)) : prev,
      );
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to mark as read');
    }
  }

  async function handleDeleteRule(id: string) {
    try {
      await deleteAlertRule(id);
      setRules((prev) => (prev ? prev.filter((r) => r.id !== id) : prev));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to delete rule');
    }
  }

  async function handleCreateRule(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const needsThreshold = triggerType === 'grade_below' || triggerType === 'grade_drop_pct';
      await createAlertRule({
        triggerType,
        channel,
        studentId: studentId || undefined,
        thresholdValue: needsThreshold && threshold ? Number(threshold) : undefined,
      });
      loadAll();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create rule');
    } finally {
      setSubmitting(false);
    }
  }

  const needsThreshold = triggerType === 'grade_below' || triggerType === 'grade_drop_pct';

  return (
    <div className="alerts-page">
      {error && <p className="auth-error">{error}</p>}

      <section className="alerts-section">
        <h2>Notifications</h2>
        {notifications === null && <p>Loading…</p>}
        {notifications && notifications.length === 0 && <p>No notifications yet.</p>}
        {notifications && notifications.length > 0 && (
          <ul className="notification-list">
            {notifications.map((n) => (
              <li key={n.id} className={n.readAt ? 'notification-item' : 'notification-item unread'}>
                <div>
                  <p className="notification-message">{n.message}</p>
                  <p className="notification-date">{new Date(n.createdAt).toLocaleDateString()}</p>
                </div>
                {!n.readAt && (
                  <button type="button" className="link-button" onClick={() => handleMarkRead(n.id)}>
                    Mark read
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="alerts-section">
        <h2>Alert rules</h2>
        {rules === null && <p>Loading…</p>}
        {rules && rules.length === 0 && <p>No rules configured yet.</p>}
        {rules && rules.length > 0 && (
          <ul className="rule-list">
            {rules.map((r) => (
              <li key={r.id} className="rule-item">
                <div>
                  <p className="rule-trigger">{TRIGGER_LABELS[r.triggerType]}</p>
                  <p className="rule-detail">
                    {studentName(r.studentId)}
                    {r.thresholdValue != null && ` · ${r.thresholdValue}%`} · {CHANNEL_LABELS[r.channel]}
                  </p>
                </div>
                <button type="button" className="link-button" onClick={() => handleDeleteRule(r.id)}>
                  Delete
                </button>
              </li>
            ))}
          </ul>
        )}

        <form className="rule-form" onSubmit={handleCreateRule}>
          <div className="field">
            <label htmlFor="triggerType">Alert me when</label>
            <select
              id="triggerType"
              value={triggerType}
              onChange={(e) => setTriggerType(e.target.value as TriggerType)}
            >
              {Object.entries(TRIGGER_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          {needsThreshold && (
            <div className="field">
              <label htmlFor="threshold">Threshold (%)</label>
              <input
                id="threshold"
                type="number"
                min={0}
                max={100}
                value={threshold}
                onChange={(e) => setThreshold(e.target.value)}
              />
            </div>
          )}

          <div className="field">
            <label htmlFor="student">Student</label>
            <select id="student" value={studentId} onChange={(e) => setStudentId(e.target.value)}>
              <option value="">All children</option>
              {students.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.firstName} {s.lastName}
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label htmlFor="channel">Notify me via</label>
            <select id="channel" value={channel} onChange={(e) => setChannel(e.target.value as AlertChannel)}>
              {Object.entries(CHANNEL_LABELS).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          <button className="primary" type="submit" disabled={submitting}>
            {submitting ? 'Adding…' : 'Add rule'}
          </button>
        </form>
      </section>
    </div>
  );
}
