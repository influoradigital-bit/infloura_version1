// KNOWN-BAD 03 — same class expressed as a hoisted function DECLARATION rather than a
// const arrow. Identity is still minted once per render of the parent.
import React from 'react';

export default function SettingsPage() {
  const [tab, setTab] = React.useState('a');

  function DetailPanel() {
    return (
      <section>
        <input defaultValue="" />
      </section>
    );
  }

  return (
    <div>
      <button onClick={() => setTab('b')}>{tab}</button>
      <DetailPanel />
    </div>
  );
}
