import {extensionRequest, request as managerRequest} from '@manager-console/transport.js';

const liveReads = new Set(['sessions', 'thread', 'thread.usage']);
const liveWrites = new Set(['policy.draft', 'policy.publish', 'policy.visibility',
  'credential.set', 'credential.remove', 'reasoning.set']);

export async function request(operation, input = {}, signal, gameConnected = false) {
  if (liveWrites.has(operation)) {
    return extensionRequest('wenzhouai', operation, input, signal);
  }
  if (gameConnected && liveReads.has(operation)) {
    try {
      return await extensionRequest('wenzhouai', operation, input, signal);
    } catch (error) {
      if (![502, 503, 504].includes(error.status)) throw error;
    }
  }
  return managerRequest('/wenzhouai/' + operation, input, {signal});
}
