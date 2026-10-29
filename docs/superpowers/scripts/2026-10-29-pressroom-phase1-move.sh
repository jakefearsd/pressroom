#!/usr/bin/env bash
# Pressroom rebrand, Phase 1: move the Java packages and rename Roller* code
# identifiers in one mechanical step. Spec:
# docs/superpowers/specs/2026-10-29-pressroom-rebrand-design.md; plan:
# docs/superpowers/plans/2026-10-29-pressroom-phase1-code-identity.md (Task 2).
#
# Usage, from anywhere inside the repository (the script works from the
# top level):
#
#   docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh --dry-run
#   docs/superpowers/scripts/2026-10-29-pressroom-phase1-move.sh
#
# --dry-run prints MOVES, IDENTIFIERS, SUBSTITUTED_FILES, KEPT_LINES and
# NOT_RENAMED (plus MAVEN_COORDINATES and LEFTOVER_LEGACY) and changes
# nothing. Without it the script moves, rewrites and stages (git add); it
# never commits.
#
# Refusals (non-zero exit, nothing changed): a dirty working tree, untracked
# files included; a destination path that already exists or that two moves
# share; a Pressroom* target name that already exists as a tracked file or as
# a word in the file set; a Maven coordinate count other than the expected one.
#
# Recovery after a failed real run (the tree was clean when it started):
#   git reset --hard HEAD && git clean -fd -- app it-selenium
#
# The rules, numbered as in the plan:
#  1. File set: tracked files under app/ it-selenium/ .github/ config/, plus
#     a fixed list of root files and docs/dev/*.md; minus LICENSE.txt,
#     NOTICE.txt, bin/db/migrations/**, docs/superpowers/**, docs/examples/**.
#     Binary files (a NUL byte in the first 8 KB) move but are never rewritten.
#  2. Moves: every file-set path containing /org/apache/roller/ moves to
#     /com/wikantik/pressroom/ (the weblogger level folds away), and a
#     basename whose stem is on the identifier map is renamed by it.
#  3. Identifier map: stems of tracked Roller*.java files under app/ and
#     it-selenium/, nested Roller* types declared in those files, plus
#     RollerEditor, RollerContent, RollerPU and three legacy words
#     (RollerFactory, RollerDB, RollerSecurity). RollerX -> PressroomX. Wiki
#     page names and RollerSSO are never on it.
#  4. Substitutions, per line, skipping any line containing rebrand:keep.
#  5. Maven artifactIds in the three poms.
#  6. git add -A over the (moved) file set.
set -euo pipefail

usage() { echo "usage: $0 [--dry-run]" >&2; exit 2; }
refuse() { echo "REFUSED: $*" >&2; exit 1; }

DRY_RUN=0
if [ $# -gt 1 ]; then usage; fi
case "${1:-}" in
    --dry-run) DRY_RUN=1 ;;
    "") ;;
    *) usage ;;
esac

command -v perl >/dev/null || refuse "perl is required"
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"

# --- Refusal: dirty tree. Untracked files count: one left under
# src/main/java/org/apache/roller would be compiled from the old package
# after the move. Ignored files (target/) do not show here and do not matter.
DIRTY=$(git status --porcelain)
if [ -n "$DIRTY" ]; then
    printf '%s\n' "$DIRTY" | head -20 >&2
    refuse "working tree is dirty (git status --porcelain is not empty)"
fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# NUL-separated git output to newline lists; refuse a path holding a newline.
nl_list() { perl -0ne 'chomp; die "path with a newline: $_\n" if /\n/; print "$_\n"'; }

# --- Rule 1: the file set.
EXTRA_FILES=(pom.xml docker-compose.yml nbactions.xml version-rules.xml
             CLAUDE.md README.md docker_deployment.md RollerSSO.md
             deploy/.env.example)
for f in "${EXTRA_FILES[@]}"; do
    git ls-files --error-unmatch -- "$f" >/dev/null 2>&1 \
        || refuse "listed file $f is not tracked; the rules were written for a different tree"
done
{
    git ls-files -z -- app it-selenium .github config | nl_list
    git ls-files -z -- "${EXTRA_FILES[@]}" | nl_list
    git ls-files -z -- docs/dev | nl_list | grep -E '^docs/dev/[^/]+\.md$' || true
} | grep -vE '^(bin/db/migrations/|docs/superpowers/|docs/examples/)|(^|/)(LICENSE|NOTICE)\.txt$' \
  | LC_ALL=C sort -u > "$WORK/fileset"

# Text subset: regular files (no symlinks) without a NUL in the first 8 KB.
perl -ne '
    chomp; my $p = $_;
    next if -l $p || ! -f $p;
    open(my $fh, "<:raw", $p) or die "cannot read $p: $!\n";
    my $n = read($fh, my $buf, 8192); close $fh;
    die "cannot read $p\n" unless defined $n;
    print "$p\n" if index($buf, "\0") < 0;
' "$WORK/fileset" > "$WORK/textset"

# --- Rule 3: the identifier map.
git ls-files -z -- app it-selenium | nl_list \
    | perl -ne 'chomp; s{.*/}{}; print "$1\n" if /^(Roller[^\/]*)\.java$/' \
    | LC_ALL=C sort -u > "$WORK/java-stems"
while IFS= read -r stem; do
    [[ "$stem" =~ ^Roller[A-Z][A-Za-z0-9_]*$ ]] \
        || refuse "Roller*.java stem '$stem' is not a RollerX identifier; renaming it as a word is unsafe"
done < "$WORK/java-stems"
# 3a (added at the dry-run review): nested types. A Roller* type declared
# inside another file (RollerMessages.RollerMessage, RollerViewResolver's
# RollerLayoutView, JPAPersistenceStrategy's RollerPersistenceUnitInfo) has
# no file of its own, so the stems above miss it, and a leftover Roller[A-Z]
# token in main-java/test fails OldNameInventoryTest.layerOneIsEmptyOnceMoved.
git ls-files -z -- app it-selenium | nl_list | grep -E '\.java$' \
    | perl -ne 'chomp; open(my $f, "<", $_) or die "$_: $!\n";
                while (<$f>) { print "$1\n" while /\b(?:class|interface|enum|record)\s+(Roller[A-Z]\w*)/g }' \
    | LC_ALL=C sort -u | LC_ALL=C comm -23 - "$WORK/java-stems" > "$WORK/nested-types"
# 3b: the fixed extras. RollerEditor/RollerContent are the editor bundles' JS
# globals (app/frontend/build.mjs), RollerPU the persistence-unit name.
FIXED_EXTRAS=(RollerEditor RollerContent RollerPU)
# 3c (added at the dry-run review): legacy words in comments and one test
# fixture that are no class today but match the inventory's CLASS pattern in
# Layer 1 areas: "RollerFactory" (business/package-info.java), "RollerDB"
# (RollerUserDetails javadoc), "RollerSecurity" (a sample log line in
# LogPatternTest). Renaming them is harmless; leaving them fails
# layerOneIsEmptyOnceMoved. Each occurs exactly once in the file set.
PROSE_EXTRAS=(RollerFactory RollerDB RollerSecurity)
{ cat "$WORK/java-stems" "$WORK/nested-types"; printf '%s\n' "${FIXED_EXTRAS[@]}" "${PROSE_EXTRAS[@]}"; } \
    | LC_ALL=C sort -u > "$WORK/idents"
for never in RollerSSO RollerHub RollerMcp RollerOnDocker RollerAdminAccountRunbook \
             RollerTelemetryHandoffSpec RollerBlogPlatform RollerW3tPlugins; do
    if grep -qxF "$never" "$WORK/idents"; then
        refuse "$never is a wiki/doc name and must not be on the identifier map"
    fi
done

# Refusal: a target name that already exists as a tracked file.
git ls-files -z | nl_list | perl -ne 'chomp; s{.*/}{}; s/\..*//; print "$_\n"' \
    | LC_ALL=C sort -u > "$WORK/tracked-stems"
sed 's/^Roller/Pressroom/' "$WORK/idents" | LC_ALL=C sort -u > "$WORK/targets"
CLASH=$(LC_ALL=C comm -12 "$WORK/targets" "$WORK/tracked-stems")
[ -z "$CLASH" ] || refuse "target name(s) already exist as tracked files: $(echo $CLASH)"

# --- Rule 2: the moves, as "old<TAB>new" lines.
perl -e '
    my ($idents, $fileset) = @ARGV;
    open(my $i, "<", $idents) or die; my %map;
    while (<$i>) { chomp; (my $p = $_) =~ s/^Roller/Pressroom/; $map{$_} = $p; }
    open(my $f, "<", $fileset) or die;
    while (my $old = <$f>) {
        chomp $old;
        next unless index($old, "/org/apache/roller/") >= 0;
        my $new = $old;
        $new =~ s{/org/apache/roller/weblogger/}{/com/wikantik/pressroom/}
            or $new =~ s{/org/apache/roller/}{/com/wikantik/pressroom/};
        my ($dir, $base) = $new =~ m{^(.*/)([^/]+)$};
        my ($stem, $rest) = $base =~ m{^([^.]*)(.*)$};
        $base = $map{$stem} . $rest if exists $map{$stem};
        warn "WARNING: moved file keeps a Roller basename: $old\n" if $base =~ /^Roller/;
        print "$old\t$dir$base\n";
    }
' "$WORK/idents" "$WORK/fileset" > "$WORK/moves"

cut -f2 "$WORK/moves" | LC_ALL=C sort | uniq -d > "$WORK/dup-dests"
[ ! -s "$WORK/dup-dests" ] || refuse "two moves share a destination: $(head -5 "$WORK/dup-dests" | tr '\n' ' ')"
while IFS=$'\t' read -r old new; do
    if [ -e "$new" ] || [ -L "$new" ]; then refuse "destination exists: $new (from $old)"; fi
    case "$new" in *org/apache/roller*) refuse "destination still holds the old root: $new";; esac
done < "$WORK/moves"

# --- Rules 4 and 5: one perl program for the dry run and the real run, so
# the dry run exercises exactly the rules that will be applied.
#   subst.pl MODE IDENTS FILELIST OUTDIR
#   MODE: "dry" analyses only; "apply" also writes changed files in place
#   (same inode, so file modes are kept).
cat > "$WORK/subst.pl" <<'PERL'
use strict; use warnings;
my ($mode, $idents, $list, $out) = @ARGV;
open(my $ih, "<", $idents) or die "idents: $!";
my @ids = map { chomp; $_ } <$ih>;
my %onmap = map { $_ => 1 } @ids;
# Longest first, so RollerViewResolverTest is tried before RollerViewResolver.
my $alt = join "|", map { quotemeta } sort { length($b) <=> length($a) || $a cmp $b } @ids;
my $idre = qr/\b($alt)\b/;
my $tgt = join "|", map { (my $t = $_) =~ s/^Roller/Pressroom/; quotemeta $t } @ids;
my $tgtre = qr/\b($tgt)\b/;
my %poms = map { $_ => 1 } ("pom.xml", "app/pom.xml", "it-selenium/pom.xml");
my @maven = (
    ["<artifactId>roller-project</artifactId>",        "<artifactId>pressroom-project</artifactId>", 3],
    ["<artifactId>roller-webapp</artifactId>",         "<artifactId>pressroom</artifactId>", 2],
    ["<artifactId>roller-selenium-tests</artifactId>", "<artifactId>pressroom-selenium-tests</artifactId>", 1],
);
my %mavencount;

sub rewrite {
    my ($line, $path) = @_;
    $line =~ s/org\.apache\.roller\.weblogger/com.wikantik.pressroom/g;
    $line =~ s{org/apache/roller/weblogger}{com/wikantik/pressroom}g;
    $line =~ s/org\.apache\.roller/com.wikantik.pressroom/g;
    $line =~ s{org/apache/roller}{com/wikantik/pressroom}g;
    $line =~ s/$idre/'Pressroom' . substr($1, 6)/ge;
    if ($poms{$path}) {
        for my $m (@maven) {
            my ($from, $to) = @$m;
            my $n = 0;
            $n++ while $line =~ s/\Q$from\E/$to/;
            $mavencount{$from} += $n;
        }
    }
    return $line;
}

my (%notrenamed, @kept, @changed, @leftover, @clash);
open(my $lh, "<", $list) or die "list: $!";
while (my $path = <$lh>) {
    chomp $path;
    open(my $fh, "<:raw", $path) or die "$path: $!";
    my @lines = <$fh>; close $fh;
    my $changed = 0;
    for my $n (0 .. $#lines) {
        my $line = $lines[$n];
        while ($line =~ /\b(Roller[A-Z]\w*)/g) { $notrenamed{$1}++ unless $onmap{$1}; }
        while ($line =~ /$tgtre/g) { push @clash, "$path:" . ($n + 1) . " $1"; }
        if ($line =~ /rebrand:keep/) {
            my $would = rewrite($line, "") ne $line ? "rule would fire" : "no rule fires";
            push @kept, "$path:" . ($n + 1) . "\t[$would]";
            next;
        }
        my $new = rewrite($line, $path);
        if ($new =~ /org\W{1,6}apache\W{1,6}roller/i) {
            (my $t = $new) =~ s/\s+$//;
            push @leftover, "$path:" . ($n + 1) . "\t$t";
        }
        if ($new ne $line) { $lines[$n] = $new; $changed = 1; }
    }
    if ($changed) {
        push @changed, $path;
        if ($mode eq "apply") {
            open(my $wh, ">:raw", $path) or die "write $path: $!";
            print $wh @lines; close $wh or die "close $path: $!";
        }
    }
}
sub dump_to { my ($name, @rows) = @_; open(my $o, ">", "$out/$name") or die; print $o map { "$_\n" } @rows; close $o; }
dump_to("changed", @changed);
dump_to("kept", @kept);
dump_to("leftover", @leftover);
dump_to("clash", @clash);
dump_to("notrenamed", map { "$_ $notrenamed{$_}" } sort { $notrenamed{$b} <=> $notrenamed{$a} || $a cmp $b } keys %notrenamed);
dump_to("maven", map { "$_->[0] x$_->[2] expected, " . ($mavencount{$_->[0]} // 0) . " found" } @maven);
my $bad = grep { ($mavencount{$_->[0]} // 0) != $_->[2] } @maven;
exit($bad ? 3 : 0);
PERL

# Analysis pass over the unmoved tree (always; it also feeds the refusals).
mkdir -p "$WORK/dry"
MAVEN_OK=1
perl "$WORK/subst.pl" dry "$WORK/idents" "$WORK/textset" "$WORK/dry" || MAVEN_OK=0
[ ! -s "$WORK/dry/clash" ] || refuse "target identifier(s) already present in the file set: $(head -5 "$WORK/dry/clash" | tr '\n' ' ')"
[ "$MAVEN_OK" = 1 ] || { cat "$WORK/dry/maven" >&2; refuse "Maven coordinate counts differ from the rules"; }

if [ "$DRY_RUN" = 1 ]; then
    echo "MOVES $(wc -l < "$WORK/moves")"
    head -20 "$WORK/moves" | sed 's/\t/ -> /'
    echo
    echo "IDENTIFIERS $(wc -l < "$WORK/idents")"
    printf '%s\n' "${FIXED_EXTRAS[@]}" > "$WORK/fixed-extras"
    printf '%s\n' "${PROSE_EXTRAS[@]}" > "$WORK/prose-extras"
    while IFS= read -r id; do
        if grep -qxF "$id" "$WORK/java-stems"; then src="Roller*.java file"
        elif grep -qxF "$id" "$WORK/nested-types"; then src="nested type"
        elif grep -qxF "$id" "$WORK/fixed-extras"; then src="fixed extra"
        else src="legacy word in a comment/fixture"; fi
        echo "$id -> Pressroom${id#Roller}  [$src]"
    done < "$WORK/idents"
    echo
    echo "SUBSTITUTED_FILES $(wc -l < "$WORK/dry/changed")"
    echo "  (file set $(wc -l < "$WORK/fileset"), of which text $(wc -l < "$WORK/textset"))"
    echo
    echo "KEPT_LINES $(wc -l < "$WORK/dry/kept")"
    cat "$WORK/dry/kept"
    echo
    echo "NOT_RENAMED $(wc -l < "$WORK/dry/notrenamed")"
    cat "$WORK/dry/notrenamed"
    echo
    echo "MAVEN_COORDINATES"
    cat "$WORK/dry/maven"
    echo
    echo "LEFTOVER_LEGACY $(wc -l < "$WORK/dry/leftover")  (old root still present after the rules; a human decides)"
    cat "$WORK/dry/leftover"
    exit 0
fi

# --- Real run. Moves first, one git mv per file.
while IFS=$'\t' read -r old new; do
    mkdir -p "$(dirname "$new")"
    git mv -- "$old" "$new"
done < "$WORK/moves"
# Remove the emptied org/ directories (only empty ones; anything left behind
# stays visible).
cut -f1 "$WORK/moves" | sed 's#/org/apache/roller/.*##' | LC_ALL=C sort -u | while IFS= read -r base; do
    if [ -d "$base/org" ]; then find "$base/org" -depth -type d -empty -delete; fi
done

# Rewrite the text files at their new paths.
perl -e '
    my ($moves, $textset) = @ARGV; my %m;
    open(my $mh, "<", $moves) or die; while (<$mh>) { chomp; my ($o, $n) = split /\t/; $m{$o} = $n; }
    open(my $th, "<", $textset) or die; while (<$th>) { chomp; print exists $m{$_} ? $m{$_} : $_, "\n"; }
' "$WORK/moves" "$WORK/textset" > "$WORK/textset-moved"
mkdir -p "$WORK/apply"
perl "$WORK/subst.pl" apply "$WORK/idents" "$WORK/textset-moved" "$WORK/apply" \
    || { cat "$WORK/apply/maven" >&2; echo "ERROR: Maven counts changed during the run" >&2; exit 1; }

# --- Rule 6: stage the moved file set (git mv already staged the renames).
perl -e '
    my ($moves, $fileset) = @ARGV; my %m;
    open(my $mh, "<", $moves) or die; while (<$mh>) { chomp; my ($o, $n) = split /\t/; $m{$o} = $n; }
    open(my $fh, "<", $fileset) or die; while (<$fh>) { chomp; print exists $m{$_} ? $m{$_} : $_, "\n"; }
' "$WORK/moves" "$WORK/fileset" > "$WORK/fileset-moved"
git --literal-pathspecs add -A --pathspec-from-file="$WORK/fileset-moved"

echo "MOVED $(wc -l < "$WORK/moves") files; rewrote $(wc -l < "$WORK/apply/changed") files"
git diff --cached --shortstat
UNSTAGED=$(git status --porcelain | grep -v '^[RMAD] ' || true)
if [ -n "$UNSTAGED" ]; then
    echo "WARNING: changes outside the index:" >&2
    printf '%s\n' "$UNSTAGED" | head -20 >&2
    exit 1
fi
echo "Staged. Review with git diff --cached; the script does not commit."
