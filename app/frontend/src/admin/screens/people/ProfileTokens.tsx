import { useQueryClient } from '@tanstack/react-query';
import { call } from '../../api/client';
import { useMe } from '../../api/queries';
import { useApi } from '../../context';
import { PageTitle } from '../../frame/PageTitle';
import { useT } from '../../i18n/messages';
import { useAllWeblogs, useMyTokens } from './queries';
import { TokenSection } from './tokens';

/** Profile -> API tokens (ProfileTokens.jsp): the signed-in account's own tokens. */
export function ProfileTokens() {
  const t = useT();
  const api = useApi();
  const queries = useQueryClient();
  const tokens = useMyTokens();
  const me = useMe();
  const everyWeblog = useAllWeblogs(me.data?.globalAdmin === true);
  // A site admin may pin any weblog, anyone else one they belong to (TokenPins.choices).
  const choices = me.data?.globalAdmin === true
    ? (everyWeblog.data ?? []).map((w) => ({ handle: w.handle ?? '', name: w.name ?? '' }))
    : (me.data?.weblogs ?? []).map((w) => ({ handle: w.handle, name: w.name }));

  return (
    <div id="profileTokensPage">
      <PageTitle>{t('apiTokens.profile.title')}</PageTitle>
      <p className="subtitle">{t('apiTokens.profile.subtitle')}</p>
      <TokenSection listHeading={t('apiTokens.live')} tokens={tokens.data} loadError={tokens.error} choices={choices}
                    mint={(body) => call(api.POST('/v1/tokens', { body }))}
                    revoke={(id) => call(api.DELETE('/v1/tokens/{id}', { params: { path: { id } } }))}
                    onChanged={() => queries.invalidateQueries({ queryKey: ['myTokens'] })} />
    </div>
  );
}
