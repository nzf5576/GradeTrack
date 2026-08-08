import { useState } from 'react';
import Gradebook from './admin/Gradebook';
import OrgSetup from './admin/OrgSetup';
import StudentsGuardians from './admin/StudentsGuardians';

type Tab = 'org' | 'students' | 'gradebook';

const TABS: { id: Tab; label: string }[] = [
  { id: 'org', label: 'Org setup' },
  { id: 'students', label: 'Students & guardians' },
  { id: 'gradebook', label: 'Gradebook' },
];

export default function Admin() {
  const [tab, setTab] = useState<Tab>('org');

  return (
    <div className="admin-page">
      <div className="admin-tabs">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={t.id === tab ? 'admin-tab active' : 'admin-tab'}
            onClick={() => setTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'org' && <OrgSetup />}
      {tab === 'students' && <StudentsGuardians />}
      {tab === 'gradebook' && <Gradebook />}
    </div>
  );
}
