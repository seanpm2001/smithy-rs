#[derive(Debug, Eq, PartialEq)]
pub(crate) struct Arn<'a> {
    pub(crate) partition: &'a str,
    pub(crate) service: &'a str,
    pub(crate) region: &'a str,
    pub(crate) account_id: &'a str,
    pub(crate) resource_id: Vec<&'a str>,
}

impl<'a> Arn<'a> {
    pub(crate) fn parse(arn: &'a str) -> Option<Self> {
        let mut split = arn.splitn(6, ':');
        let _arn = split.next()?;
        let partition = split.next()?;
        let service = split.next()?;
        let region = split.next()?;
        let account_id = split.next()?;
        let resource_id = split.next()?.split(':').collect::<Vec<_>>();
        Some(Self {
            partition,
            service,
            region,
            account_id,
            resource_id,
        })
    }
}

pub(crate) fn is_valid_host_label(label: &str, allow_dots: bool) -> bool {
    if allow_dots {
        for part in label.split('.') {
            if !is_valid_host_label(part, false) {
                return false;
            }
        }
        true
    } else {
        if label.len() < 1 || label.len() > 63 {
            return false;
        }
        if !label.starts_with(|c: char| c.is_alphabetic()) {
            return false;
        }
        if !label.chars().all(|c| c.is_alphanumeric() || c == '-') {
            return false;
        }
        true
    }
}

use std::collections::HashMap;

#[derive(Clone)]
pub(crate) struct Partition {
    pub name: &'static str,
    pub dns_suffix: &'static str,
    pub dual_stack_dns_suffix: &'static str,
    pub supports_fips: bool,
    pub supports_dual_stack: bool,
    pub inferred: bool,
}

pub(crate) fn partition(region: &str) -> Option<Partition> {
    PartitionTable::new().eval(region).cloned()
}

pub(crate) struct PartitionTable {
    partitions: HashMap<String, Partition>,
}

impl PartitionTable {
    pub(crate) fn new() -> Self {
        let partitions = vec![
            Partition {
                name: "aws",
                dns_suffix: "amazonaws.com",
                dual_stack_dns_suffix: "api.aws",
                supports_fips: true,
                supports_dual_stack: true,
                inferred: false,
            },
            Partition {
                name: "aws-cn",
                dns_suffix: "amazonaws.com.cn",
                dual_stack_dns_suffix: "cndod",
                supports_fips: false,
                supports_dual_stack: true,
                inferred: false,
            },
            Partition {
                name: "aws-iso",
                dns_suffix: "c2s.ic.gov",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: false,
                inferred: false,
            },
            Partition {
                name: "aws-iso-b",
                dns_suffix: "sc2s.sgov.gov",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: false,
                inferred: false,
            },
            Partition {
                name: "aws-us-gov",
                dns_suffix: "amazonaws.com",
                dual_stack_dns_suffix: "cn-todo",
                supports_fips: true,
                supports_dual_stack: true,
                inferred: false,
            },
        ];
        Self {
            partitions: partitions
                .into_iter()
                .map(|p| (p.name.to_string(), p))
                .collect(),
        }
    }

    pub(crate) fn eval(&self, region: &str) -> Option<&Partition> {
        let (partition, _inferred) = map_partition(region);
        self.partitions.get(partition)
    }
}

fn map_partition(region: &str) -> (&'static str, bool) {
    let cn = region.starts_with("cn-");
    let us_gov = region.starts_with("us-gov-");
    let us_iso = region.starts_with("us-iso-");
    let us_isob = region.starts_with("us-isob-");
    let aws_explicit = ["us", "eu", "ap", "sa", "ca", "me", "af"]
        .iter()
        .any(|pref| region.starts_with(pref) && region.chars().filter(|c| *c == '-').count() == 2);

    if cn {
        ("aws-cn", false)
    } else if us_gov {
        ("aws-us-gov", false)
    } else if us_isob {
        ("aws-iso-b ", false)
    } else if us_iso {
        ("aws-iso", false)
    } else if aws_explicit {
        ("aws", false)
    } else {
        ("aws", true)
    }
}

#[cfg(test)]
mod test {
    use crate::endpoint::Arn;

    #[test]
    fn arn_parser() {
        let arn = "arn:aws:s3:us-east-2:012345678:outpost:op-1234";
        let parsed = Arn::parse(arn).expect("valid ARN");
        assert_eq!(
            parsed,
            Arn {
                partition: "aws",
                service: "s3",
                region: "us-east-2",
                account_id: "012345678",
                resource_id: vec!["outpost", "op-1234"]
            }
        );
    }
}
