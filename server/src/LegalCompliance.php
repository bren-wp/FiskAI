<?php
declare(strict_types=1);

/** Narrow statutory baseline, not a transaction classification or issuance authorization.
 * Caller must establish covered domestic B2B scope and historical subject status.
 */
final class LegalCompliance {
    public static function validateDate(string $date): void {
        if (!preg_match('/\A[0-9]{4}-[0-9]{2}-[0-9]{2}\z/D', $date)
            || !checkdate((int)substr($date,5,2),(int)substr($date,8,2),(int)substr($date,0,4))) {
            throw new InvalidArgumentException('Invalid effective date.');
        }
    }

    /** Resolve a validated ISO date in a half-open interval; gaps fail closed. */
    public static function ruleForDate(string $date): ?array {
        self::validateDate($date);
        static $rules = null;
        $rules ??= require __DIR__.'/LegalRuleCatalog.php';
        $match = null;
        foreach ($rules as $rule) {
            if ($date >= $rule['from'] && $date < $rule['until']) {
                if ($match !== null) throw new LogicException('Overlapping legal rules.');
                $match = $rule;
            }
        }
        return $match;
    }

    public static function evaluate(string $date, string $subject, string $scope): array {
        self::validateDate($date);
        if (!in_array($subject,['VAT','NON_VAT_COVERED','UNKNOWN'],true)
            || !in_array($scope,['STANDARD_DOMESTIC_B2B','UNRESOLVED'],true)) {
            throw new InvalidArgumentException('Invalid legal context.');
        }
        $rule = self::ruleForDate($date);
        $version = $rule['version'] ?? null;
        $issue = $receive = 'REVIEW_REQUIRED';
        if ($version !== null && $scope === 'STANDARD_DOMESTIC_B2B' && $subject !== 'UNKNOWN') {
            $receive = 'REQUIRED';
            $issue = ($subject === 'VAT' ? $rule['vatIssue'] : $rule['nonVatIssue'])
                ? 'REQUIRED' : 'NOT_REQUIRED_BY_THIS_RULE';
        }
        return ['version'=>$version,'issue'=>$issue,'receive'=>$receive,
            'fiscalizeIssue'=>$issue,'fiscalizeReceive'=>$receive];
    }
}
