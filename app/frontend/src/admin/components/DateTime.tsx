import { createContext, useContext } from 'react';

/** The action weblog's time zone for DateTime; undefined where no weblog is in play. */
export const TimeZoneContext = createContext<string | undefined>(undefined);

const PATTERN: Intl.DateTimeFormatOptions = {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
};

/**
 * `yyyy-MM-dd HH:mm` in the weblog's zone, as <rc:date> prints it. With no weblog zone
 * (nobody's clock in particular) it is UTC and names the zone, so "14:05" cannot be
 * mistaken for the reader's own time.
 */
export function formatDateTime(value: Date | string | number, timeZone?: string): string {
  const instant = new Date(value);
  const zone = timeZone ?? 'UTC';
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', { ...PATTERN, timeZone: zone }).formatToParts(instant)
      .map((part) => [part.type, part.value])
  );
  const text = `${String(parts['year'])}-${String(parts['month'])}-${String(parts['day'])} ` +
    `${String(parts['hour'])}:${String(parts['minute'])}`;
  return timeZone === undefined ? `${text} UTC` : text;
}

/** `<time datetime=UTC ISO class="data">`: the markup <rc:date> emits. Renders nothing for an unparseable value. */
export function DateTime({ value, timeZone }: { value: Date | string | number | null | undefined; timeZone?: string }) {
  const contextZone = useContext(TimeZoneContext);
  if (value === null || value === undefined) {
    return null;
  }
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) {
    return null;
  }
  return (
    <time dateTime={instant.toISOString()} className="data">
      {formatDateTime(instant, timeZone ?? contextZone)}
    </time>
  );
}
