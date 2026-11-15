let count = 0;
const counterBtn = document.getElementById('counterBtn');
const nostrBtn = document.getElementById('nostrBtn');
const output = document.getElementById('output');

counterBtn?.addEventListener('click', () => {
  count += 1;
  counterBtn.textContent = 'Clicks: ' + count;
  output.textContent = 'Interactive state updated (' + count + ')';
});

nostrBtn?.addEventListener('click', async () => {
  if (!window.nostr) {
    output.textContent = 'NIP-07 disabled for this view.';
    return;
  }
  try {
    const pubkey = await window.nostr.getPublicKey();
    output.textContent = 'Active Nostr pubkey: ' + pubkey;
  } catch (err) {
    output.textContent = 'Signer response: ' + err.message;
  }
});
